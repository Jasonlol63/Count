# Bank Process：Bank Balance（一次性 Contra 结余）

Add/Edit Process 弹窗新增一个可选的 "Bank Balance" 金额输入框。Supplier/Customer 之间正常交易
记的都是精确到 0.00 的完整金额，但实际操作中双方之间可能会留一笔几块钱的零头（不会精确归零）。
填了这个字段后，系统会自动帮用户生成一笔 Contra 交易，把这笔零头结平：Customer 付款方
`-amount`，Supplier 收款方 `+amount`。

前端设计/交互细节见 `Count-frontend` 仓库的
[`docs/bank-process-bank-balance.md`](../../Count-frontend/docs/bank-process-bank-balance.md)。
这份文档只记录后端部分。

## 目标行为

- **Add Process**：填了 Bank Balance（且 > 0）→ 流程创建成功后追加生成一笔 CONTRA 交易；没填 →
  跳过，行为不变。
- **Edit Process**：
  - 该流程还没有关联的 Bank Balance 交易 → 字段可编辑，填了保存后生成新的 CONTRA。
  - **已经有关联交易** → 字段锁定，不接受新值——哪怕请求里带了新的 `bankBalance`，后端也**不会**
    用它覆盖或重新生成，必须先调用删除接口把旧的那笔删掉，才能再次创建。这个"已锁定就忽略"的判断
    在后端做，不信任前端传来的值（前端表单本来就会把字段设成只读，但服务端这一层是双重保险）。
  - 提供一个独立的"删除 Bank Balance"接口，删除关联的 CONTRA 交易后，字段重新解锁。

## 数据库改动：`transactions.bank_process_id`

新增一列，直接指回 `bank_process.id`：

- [`migrate_add_bank_process_id_to_transactions.sql`](../backend/src/main/resources/sql/migrate_add_bank_process_id_to_transactions.sql) —— 增量 migration，`information_schema` 判断后再
  `ADD COLUMN` / `ADD KEY` / `ADD CONSTRAINT`，可安全重跑。已经在本地 `testcount` 库跑过验证。
- [`schema.sql`](../backend/src/main/resources/sql/schema.sql) —— 同步更新新装库的建表语句（列、索引 `idx_txn_bank_process`、外键 `fk_txn_bank_process` 三处都加了）。

```sql
ALTER TABLE `transactions`
    ADD COLUMN `bank_process_id` INT UNSIGNED DEFAULT NULL
        COMMENT 'FK bank_process.id; direct link for one-off transactions tied to the process
                  itself (e.g. Bank Balance), independent of periodic postings
                  (see bank_process_posted_id)'
        AFTER `bank_process_posted_id`,
    ADD KEY `idx_txn_bank_process` (`bank_process_id`),
    ADD CONSTRAINT `fk_txn_bank_process`
        FOREIGN KEY (`bank_process_id`) REFERENCES `bank_process` (`id`)
        ON DELETE SET NULL;
```

**为什么不是复用 `bank_process_posted_id`**：这一列关联的是"某一期账单"
（`bank_process_accounting_posted`），是 Accounting Due 那套周期性出账逻辑专用的间接关联；
Bank Balance 是"直接挂在流程本身、跟具体某一期账单无关"的一次性结算，语义不一样，所以加了一列
新的直接外键，而不是套用旧列。

**为什么金额不存在 `bank_process` 表上**：Bank Balance 的金额永远从关联的那笔 `transactions.amount`
读出来，`bank_process` 表本身不新增任何列——单一数据源，不会出现两边数字不一致的风险。

**唯一性只在应用层保证**：一个 `bank_process` 应该"最多"关联一笔 Bank Balance 用的 CONTRA 交易，
但数据库层面没有唯一约束兜底（MySQL 做"部分唯一索引"不方便）。`findAllBankProcess` 的查询用了
相关子查询而不是普通 `LEFT JOIN`，防御万一出现异常的重复数据时把整个列表查询搞乱：

```sql
LEFT JOIN transactions bbt ON bbt.id = (
    SELECT t2.id FROM transactions t2
    WHERE t2.bank_process_id = bp.id AND t2.transaction_type = 'CONTRA'
    ORDER BY t2.id DESC LIMIT 1
)
```

## Contra 交易怎么生成的：复用现有的 `CONTRA` 类型和提交服务，没有另起一套

`CONTRA` 本来就是 `transactions.transaction_type` 枚举里的一个值，Transaction Payment 页面手动
创建 Contra 走的是
[`TransactionSubmitServiceImpl.submitTransfer`](../backend/src/main/java/com/eazycount/service/impl/TransactionSubmitServiceImpl.java)。
Bank Balance 直接复用这同一条路径，而不是自己写一套 insert 逻辑，好处是账户余额更新、跨币种
汇率处理、审批状态、实时通知事件这些都天然保持一致。

**改动方式**：给 `TransactionSubmitDTO` 加一个内部专用字段：

```java
/* Internal only — never set by the manual Transaction Payment UI. */
private Integer bankProcessId;
```

`insertAndBuildResult` / `insertApproved` 各加一个多带 `bankProcessId` 参数的重载版本，原有签名
不变、内部委托给新重载并传 `null`——这样除了 `submitTransfer` 之外的所有调用点
（`submitProfit`/`submitAdjustment`/`submitRate`）完全不用改，永远传 `null`。`submitTransfer`
改成把 `request.getBankProcessId()` 传下去，最终写进 `transactions.bank_process_id`。

`BankProcessServiceImpl.createBankBalanceContra(bankProcess, amount)`：

```java
TransactionSubmitDTO request = new TransactionSubmitDTO();
request.setTenantId(bankProcess.getTenantId());
request.setTransactionType(Transaction.TransactionType.CONTRA.name());
request.setToAccountId(bankProcess.getCustomerAccountId());   // 付款方，-amount
request.setFromAccountId(bankProcess.getSupplierAccountId()); // 收款方，+amount
request.setCurrencyCode(country.getCode());                   // 见下方"币种怎么定"
request.setAmount(amount);
request.setBankProcessId(bankProcess.getId());
transactionSubmitService.submit(request);
```

**币种怎么定**：`TransactionSubmitServiceImpl.resolveCurrency` 要求显式传 `currencyId` 或
`currencyCode`，不会自动从账户推断。Bank Balance 没有单独问用户要币种，而是直接用这个流程自己的
币种——`bank_process.country_id` 关联的 `bank_country.code` 本来就是币种代码（Add Process 表单
"Country (Currency)" 那个字段选的就是它），语义上完全对得上。

## 触发时机

`BankProcessServiceImpl`：

- `insertBankProcess`：流程 insert 成功、profit sharing 也建好之后，`bankBalance` 校验通过
  （非空、大于 0）就调用 `createBankBalanceContra`。
- `updateBankProcessDetails`：流程更新成功后，先查
  `transactionDao.findLinkedBankBalanceTransaction(tenantId, id)`，**只有查不到已有关联时**才会
  创建新的；已经存在的话，请求里的 `bankBalance` 直接忽略。

`normalizeBankBalanceAmount(raw)` 校验规则：

- `null` 或 `0` → 当作"不生成"，返回 `null`（原行为不变）。
- 负数 → 直接 `BusinessException` 拒绝，不会被静默忽略（大概率是打错了）。
- 生成前如果 `supplierAccountId`/`customerAccountId` 任一没设置 → 拒绝，不会留下"想生成但生成不了"
  的半成品状态。

## 删除：复用 Payment Maintenance 现成的删除流程，没有另写一套

新增 `BankProcessService.deleteBankBalance(id, tenantId)`（`POST /api/bank-process/delete-bank-balance`）。

删除一笔交易这件事，`MaintenanceServiceImpl.deletePaymentMaintenanceRows` 早就实现好了：先把行
归档进 `transactions_deleted`，再从 `transactions` 硬删除；`CONTRA` 本来就在它支持的类型列表
（`paymentMaintenanceTransactionTypes` / `ALLOWED_TYPES`）里，`filterDeletableIds` 只会排除
`bankProcessPostedId != null` 的行（周期账单专用），而 Bank Balance 的 CONTRA 走的是新的
`bankProcessId` 字段，`bankProcessPostedId` 本来就是 `null`，天然能通过这道过滤。

所以 `deleteBankBalance` 直接找到关联交易的 id，包一个 `MaintenancePaymentDTO` 转发给
`MaintenanceService.deletePaymentMaintenanceRows`，没有另外写归档/硬删除逻辑：

```java
Transaction linked = transactionDao.findLinkedBankBalanceTransaction(tenantId, id);
if (linked == null) {
    throw new BusinessException("No Bank Balance to delete!");
}
MaintenancePaymentDTO deleteRequest = new MaintenancePaymentDTO();
deleteRequest.setTenantId(tenantId);
deleteRequest.setTransactionIds(List.of(linked.getId()));
maintenanceService.deletePaymentMaintenanceRows(deleteRequest);
```

这样删除行为跟应用里其他地方删交易完全一致（同一份审计归档逻辑），也顺带继承了 `assertEditable`
（OFFICIAL/E_INVOICE/BLOCK 状态的流程不能删 Bank Balance，跟其他编辑操作一致）。

## 列表读取：新字段怎么传到前端

`bank-process-list` 页面的 Edit 表单没有单独的 by-id 查询接口，是直接用 list 接口
（`findAllBankProcess`）已经加载好的那一行数据构建的，所以 Bank Balance 的读取也加进了这个
list 查询：

- [`BankProcessMapper.xml`](../backend/src/main/resources/mybatis/BankProcessMapper.xml)：
  `findAllBankProcess` 加一个 LEFT JOIN（见上方"唯一性只在应用层保证"那段的 SQL），
  `BankProcessListMap` 新增 `bankBalance`/`bankBalanceTransactionId` 两个字段映射。
- [`BankProcessDTO.java`](../backend/src/main/java/com/eazycount/dto/BankProcessDTO.java)：
  `bankBalance`（add/update 请求里是要创建的金额；list 结果里是已关联的金额）、
  `bankBalanceTransactionId`（仅 list 结果用，驱动前端锁定状态）。

## 涉及文件汇总

- `backend/src/main/resources/sql/migrate_add_bank_process_id_to_transactions.sql`（新增 migration）
- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/java/com/eazycount/entity/Transaction.java`
- `backend/src/main/resources/mybatis/TransactionMapper.xml`
- `backend/src/main/java/com/eazycount/dao/TransactionDao.java`
- `backend/src/main/java/com/eazycount/dto/TransactionSubmitDTO.java`
- `backend/src/main/java/com/eazycount/service/impl/TransactionSubmitServiceImpl.java`
- `backend/src/main/java/com/eazycount/dto/BankProcessDTO.java`
- `backend/src/main/resources/mybatis/BankProcessMapper.xml`
- `backend/src/main/java/com/eazycount/service/BankProcessService.java`
- `backend/src/main/java/com/eazycount/service/impl/BankProcessServiceImpl.java`
- `backend/src/main/java/com/eazycount/controller/BankProcessController.java`

## 已知限制

- 目前只在本地 `testcount` 库验证过 migration 和 `mvn compile`；`count_real`（正式库）还没跑这个
  migration。
- 没有做端到端的自动化测试，前端联调时（Add → Edit 查看锁定态 → 删除解锁）需要人工过一遍。
