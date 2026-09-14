# Transaction：Contra Inbox 审批流程（Manager/Admin/Owner 审批 backdate 手动交易）

前端接线见 `Count-frontend` 仓库的
[`docs/transaction-contra-inbox-approval.md`](../../Count-frontend/docs/transaction-contra-inbox-approval.md)。
这份文档只记录后端部分。

## 背景

Contra Inbox 是把"手动交易提交后是否立即生效"这件事，从过去的"永远立即 APPROVED"改成按角色 +
交易日期判断，需要时进一条待审批队列。历史上（含 legacy PHP）从来没有真正实现过这个审批队列——
`docs/frontend-springboot-migration.md` 里明确写着这期"本不做 Contra Inbox 审批"，Submit 永远
立即 `APPROVED`，Inbox 前端永远返回空列表。这次是把这个功能真正落地。

## 业务规则（最终定稿）

```
if role in {Owner, Admin, Manager}:
    APPROVED                       # 绝对豁免，任何交易日期都直接生效
elif transactionDate < today:
    PENDING → 进 Contra Inbox      # 需要 Owner/Admin/Manager 审批
else:
    APPROVED                       # 今天或未来日期，直接生效
```

- 判断的是**交易单上填的 transactionDate（业务日期）**，不是提交的实际时间。
- Supervisor 及以下所有角色 + **Partnership**（不管 Partnership 账号是否被打了 `read_only` 标记）
  都属于"受日期规则约束"的一组，逻辑完全相同，没有第三种特例。
- 未来日期视同"今天"，正常直接生效，不进审批队列。
- 这套规则对**所有**手动交易类型生效（PAYMENT/CLAIM/CLEAR/CONTRA/RATE/ADJUSTMENT/PROFIT），不是
  只针对 CONTRA 类型——虽然功能沿用了"Contra Inbox"这个历史名字，但实际范围是全部手动交易类型，
  因为它们在后端共用同一个插入入口。
- 审批人权限：只有 Owner/Admin/Manager 能 approve/reject；其余角色即使账号是 Owner/Admin/Manager
  管理范围内，也没有这个入口。
- Reject = 软删除：把这笔 PENDING 交易归档进 `transactions_deleted`（`approval_status` 标记为
  `REJECTED`），再把原表那一行物理删除；不是原地改状态留着。Approve 则相反——原地把同一行的
  `approval_status` 从 `PENDING` 改成 `APPROVED`，不产生新行也不归档。
- PENDING 期间这笔交易在所有地方（余额/Cr-Dr/History/Search）都完全不可见，approve 之后才出现。

## 数据库改动

[`migrate_add_contra_inbox_approval_to_transactions_deleted.sql`](../backend/src/main/resources/sql/migrate_add_contra_inbox_approval_to_transactions_deleted.sql)
（幂等，可重复执行）给 `transactions_deleted` 加三列：

- `approval_status ENUM('APPROVED','REJECTED') NOT NULL DEFAULT 'APPROVED'` —— 注意**没有
  `PENDING`**。能进这张归档表的只有两种情况：Maintenance 原有的正常删除（删的都是已 APPROVED
  的行）、Contra Inbox 拒绝一笔 PENDING 行（拒绝动作本身就是把它标成 REJECTED 再搬过来）。默认值
  `APPROVED` 兼容所有历史/既有的 Maintenance 删除记录。
- `approved_by VARCHAR(50)` / `approved_at TIMESTAMP` —— 镜像 `transactions.approved_by`/
  `approved_at`。对 Reject 场景，这两个字段的值就是拒绝的 manager/admin/owner + 拒绝时间，和已有的
  `deleted_by`/`deleted_at` 实际是同一个人同一时间，只是保留成独立字段以贴合这张表"完整快照原始行"
  的一贯做法。

`schema.sql` 里 `transactions_deleted` 的建表定义同步加了这三列（新建库直接生效，不需要跑迁移脚本）。

`transactions.approval_status` 本身**没有改**，还是 `ENUM('APPROVED','PENDING')`——REJECTED 永远
不会出现在这张活表里，所有既有的 `= 'APPROVED'` 过滤条件都不用动。

## 后端改动

### 核心判断逻辑

[`AccessControlUtils.java`](../backend/src/main/java/com/eazycount/util/AccessControlUtils.java)
新增两个方法（角色判断统一收拢在这里，不在别处散落字符串比较）：

- `isManualTransactionApprovalExempt(role)` —— 判断 role 是否属于 `{OWNER, ADMIN, MANAGER}`。
- `requireContraInboxApprover(session)` —— approve/reject 端点用的角色门禁（先查
  `requireWritable`，再查角色白名单）。

[`TransactionSubmitServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/TransactionSubmitServiceImpl.java)：

- 私有方法 `insertApproved()` 改名为 `insertTransactionRow()`（原名字是假的——它现在不一定写
  `APPROVED`；这是私有方法，全仓库唯一引用处，改名不影响别的文件）。
- 新增私有方法 `isAutoApproved(session, transactionDate)`，就是上面那句决策树的代码化：
  `role∈{Owner,Admin,Manager} → true`，否则 `!transactionDate.isBefore(today)`。
- `insertTransactionRow()` 按这个判断二选一写 `APPROVED`（连带 `approvedBy`/`approvedAt`）或
  `PENDING`（这两个字段留 `null`）。因为这是所有手动交易类型（transfer/profit/adjustment/
  rate 的每一条腿）唯一共用的插入点，改一处即可覆盖全部类型。
- `TransactionSubmitDTO` 加了 `approvalStatus` 结果字段，提交成功后的响应会带上这次提交的最终
  状态（`APPROVED`/`PENDING`），供前端立即知道是直接生效还是进了 Contra Inbox。

### Contra Inbox 专属的新文件（DAO/Mapper/Service/Controller）

命名最终定为 `TransactionContraInbox*`（DTO/DAO/Service/ServiceImpl 独立于既有的
`MaintenanceDao`/`MaintenanceServiceImpl`，保持隔离，风格参考 `AutoRenewController` 那一套）：

- `dto/TransactionContraInboxDTO.java` —— list/approve/reject 共用的双用途 DTO（`tenantId`/`id`
  承载请求，其余字段承载 `listPending` 一行结果）。
- `dao/TransactionContraInboxDao.java` + `resources/mybatis/ContraInboxMapper.xml` —— 三个方法：
  - `findPendingRows` —— 按 `tenantId` 查所有 `approval_status = 'PENDING'` 的行，按交易日期升序。
  - `approvePendingTransaction` —— 原地 `UPDATE ... SET approval_status='APPROVED'`，只在原行仍是
    `PENDING` 时生效（并发保护）。
  - `archiveRejectedToDeleted` —— 把仍是 `PENDING` 的行插入 `transactions_deleted`，
    `approval_status`/`approved_by`/`approved_at` 显式设成 `REJECTED`/拒绝人/当前时间（不是从源行
    复制，因为源行是 PENDING、审批人字段是空的）。
- `service/TransactionContraInboxService.java` + `service/impl/TransactionContraInboxServiceImpl.java`
  —— `reject()` 严格按顺序：先 `archiveRejectedToDeleted`（失败就报错终止）→ 再复用既有的
  `MaintenanceDao.deleteByIdsAndTenantId` 物理删除，整个包在 `@Transactional` 里，和
  `MaintenanceServiceImpl` 现有的 archive-then-delete 模式完全对齐。`approve()`/`reject()`/
  `listPending()` 都先过 `requireContraInboxApprover`。
- `controller/TransactionContraInboxController.java` —— 三个独立端点（不是一个端点里塞 action
  参数分发，理由：approve/reject 是有副作用的写操作，list 是纯读，混在一起会让权限校验和错误处理
  分支变得难读）：

  ```
  POST /api/pending    → listPending(tenantId)         返回 { success, data: [...] }
  POST /api/approved    → approve({tenantId, id})       返回 { success, message }
  POST /api/rejected    → reject({tenantId, id})        返回 { success, message }
  ```

  三个端点共用同一套 `try { ... } catch (BusinessException e) { return error(e); }` 结构。

### 顺带修的一个既有缺口

`MaintenanceMapper.xml` 的 `archivePaymentMaintenanceToDeleted`（Payment Maintenance 的删除归档）
之前**没有**过滤 `approval_status = 'APPROVED'`。这次实现 Contra Inbox 时必须一起补上：一旦系统里
出现 PENDING 行，这条 SQL 会尝试把 `t.approval_status` 的值 `'PENDING'` 写进新建的
`transactions_deleted.approval_status`（这一列现在是 `ENUM('APPROVED','REJECTED')`），MySQL 会
直接报无效枚举值。实际核实后，用户能选中去删除的行来自 `findPaymentMaintenanceRows`，那条查询
本来就已经过滤了 `approval_status = 'APPROVED'`，所以这不是一个当前能被触发的活漏洞，但补上这层
过滤是必要的防御性安全网。

## 已确认但还没做的部分（留待后续）

- **Contra Inbox 列表的分组聚合**：前端 `contraInbox` 的 query key 预留了 `viewGroup`/`groupId`/
  `groupAggregate` 参数（跟 Search/History 页一致的形状），但 `listPending()` 目前只按单一
  `tenantId` 查询，没有做跨公司的聚合视图。前端调用时这几个参数会被直接忽略，效果上等同于永远只看
  当前公司自己的 Contra Inbox。
- **列表没有日期/币种筛选**：目前 `findPendingRows` 返回该租户下全部 PENDING 行，没有像 Payment
  Maintenance 那样支持 `dateFrom`/`dateTo`/`currencyCodes`/`q` 筛选。Contra Inbox 场景下预期
  PENDING 队列本身不会很长（只是backdate的手动交易），暂时够用。
- **端到端联调未做**：目前只验证到后端编译通过（`mvnw clean compile` BUILD SUCCESS）+ 前端两个
  文件 `node --check` 语法通过，还没有用真实数据跑过一遍完整流程（提交 backdate 交易 → 出现在
  Contra Inbox → approve/reject → 确认状态流转和归档结果）。

## 涉及文件

- `backend/src/main/resources/sql/migrate_add_contra_inbox_approval_to_transactions_deleted.sql`（新增）
- `backend/src/main/resources/sql/schema.sql` —— `transactions_deleted` 建表定义加三列。
- `backend/src/main/java/com/eazycount/util/AccessControlUtils.java` —— 新增角色判断方法。
- `backend/src/main/java/com/eazycount/service/impl/TransactionSubmitServiceImpl.java` —— 核心
  APPROVED/PENDING 分支逻辑；`insertApproved` 改名 `insertTransactionRow`。
- `backend/src/main/java/com/eazycount/dto/TransactionSubmitDTO.java` —— 新增 `approvalStatus`
  结果字段。
- `backend/src/main/java/com/eazycount/dto/TransactionContraInboxDTO.java`（新增）
- `backend/src/main/java/com/eazycount/dao/TransactionContraInboxDao.java`（新增）
- `backend/src/main/resources/mybatis/ContraInboxMapper.xml`（新增）
- `backend/src/main/java/com/eazycount/service/TransactionContraInboxService.java`（新增）
- `backend/src/main/java/com/eazycount/service/impl/TransactionContraInboxServiceImpl.java`（新增）
- `backend/src/main/java/com/eazycount/controller/TransactionContraInboxController.java`（新增）
- `backend/src/main/resources/mybatis/MaintenanceMapper.xml` —— 三个 archive insert 补
  `approval_status`/`approved_by`/`approved_at` 字段搬运；`archivePaymentMaintenanceToDeleted`
  补 `approval_status = 'APPROVED'` 过滤；两个 `find...DeletedRows` 查询补对应 SELECT 列。
- `backend/src/main/java/com/eazycount/dto/MaintenancePaymentDTO.java` /
  `MaintenanceBankProcessDTO.java` —— 新增 `approvalStatus`/`approvedBy`/`approvedAt` 字段。
