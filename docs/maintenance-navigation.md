# Maintenance 侧边栏导航（Spring SPA）

Maintenance 子菜单显示规则、Bank 公司入口、以及 **Spring `tenant_has_*` 与旧 PHP `company_has_*` 字段** 约定。修改 `AuthenticatedLayout`、sidebar 权限或 Maintenance 页面守卫时，**同步更新本文档**。

相关：

- Payment Maintenance 列表/软删：[`payment-maintenance-list-delete.md`](./payment-maintenance-list-delete.md)
- Session / 登录：`login-to-business-pages.md`、`frontend-springboot-migration.md` §3.3

---

## 1. Maintenance 子菜单路由

| 菜单文案（EN） | 路由 | pageKey |
|----------------|------|---------|
| Data Capture | `/capture-maintenance` | `capture-maintenance` |
| Transaction | `/transaction-maintenance` | `transaction-maintenance` |
| Payment | `/payment-maintenance` | `payment-maintenance` |
| Formula | `/formula-maintenance` | `formula-maintenance` |
| Bank Process | `/bankprocess-maintenance` | `bankprocess-maintenance` |

实现：`Count-frontend/src/components/AuthenticatedLayout.jsx`（Maintenance flyout submenu）。

---

## 2. 谁能看到 Maintenance 父菜单

| 函数 | 含义 |
|------|------|
| `showMaintenanceInSidebar(me)` | Owner / 全权限 / 有 `maintenance` 权限 / **limited maintenance** |
| `canAccessFullMaintenance(me)` | Owner、空 permissions、或含 `maintenance` |
| `canAccessLimitedMaintenance(me)` | 非 Owner、无 `maintenance` 权限，但当前 tenant 有 Game 或 Bank |

Limited 用户仍可见 **Transaction + Formula**（及 Bank 场景下的 Capture），但 **不含 Payment / Bank Process**（需 full maintenance）。

---

## 3. 各子入口显示条件

逻辑在 `AuthenticatedLayout.jsx`；下表为 2026-07-24 行为摘要。

| 子入口 | 显示条件 |
|--------|----------|
| **Data Capture** | `(fullMaintenance \|\| (limitedMaintenance && tenant_has_bank))` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Transaction** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Payment** | `fullMaintenance` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Formula** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Bank Process** | `fullMaintenance` **且** `shouldShowBankprocessMaintenanceInSidebar(me)` |

### 3.1 Bank Process 专项：`shouldShowBankprocessMaintenanceInSidebar`

文件：`Count-frontend/src/utils/company/sharedCompanyFilter.js`

Bank Process Maintenance **必须绑定具体公司**（不能 Group-only 汇总视图）。

| 场景 | 是否显示 |
|------|----------|
| Dashboard **Group Only**（只选 Group、未选 Company pill） | **否** |
| **Group All**（组内「全部公司」模式） | 组内任一公司有 **Bank** permission → **是** |
| 已选具体 Company（如 BK） | 当前 session **`tenant_has_bank === true`** → **是** |

```javascript
// 正确：Spring session 字段（含 legacy fallback）
import { sessionHasTenantBank } from "../auth/sessionTenant.js";
return sessionHasTenantBank(me);

// 错误：旧 PHP 字段，Spring current-user 不返回，恒为 undefined
return Boolean(me?.company_has_bank); // ❌ 勿用
```

### 3.2 典型故障（已修复 2026-07-24）

**现象**：Owner 登录 Bank 公司（如 BK），Maintenance 有 Payment / Formula，但 **没有 Bank Process**。

**原因**：Bank Process 侧边栏误读 `company_has_bank`；Payment 等项已用 `tenant_has_bank`。

**修复**：

- `shouldShowBankprocessMaintenanceInSidebar` → `sessionHasTenantBank(me)`
- `BankprocessMaintenancePage.jsx` 进入守卫 → `sessionHasTenantBank(user)`
- `useMaintenanceBankOnlyGuard.js` → `sessionHasTenantGame` / `sessionHasTenantBank`

---

## 4. Session 字段（Spring）

来源：`SessionUser` / `GET auth/current-user` / `switch-tenant` 响应。

| 字段 | 含义 |
|------|------|
| `tenant_id` | 当前活跃租户 numeric id（= 公司 pill id） |
| `tenant_code` | 公司 code（如 `BK`、`C168`） |
| `tenant_has_game` | 租户 permissions 含 Games / Gambling |
| `tenant_has_bank` | 租户 permissions 含 **Bank** |
| `permissions[]` | 小写功能键（`maintenance`、`process` 等） |

前端统一读取：`Count-frontend/src/utils/auth/sessionTenant.js`

```javascript
sessionHasTenantBank(me)  // me.tenant_has_bank ?? me.company_has_bank
sessionHasTenantGame(me)  // me.tenant_has_game ?? me.company_has_gambling
```

切公司 pill 时，`patchMeFromCompanyContext`（`loginScope.js`）会乐观更新 `tenant_has_*`；最终以 `current-user` / `switch-tenant` 为准。

### 4.1 勿再依赖的 PHP 字段

| 旧字段 | Spring 替代 |
|--------|-------------|
| `company_has_bank` | `tenant_has_bank` / `sessionHasTenantBank(me)` |
| `company_has_gambling` | `tenant_has_game` / `sessionHasTenantGame(me)` |
| `company_id`（session 活跃租户） | `tenant_id` |

---

## 5. Bank-only 公司路由守卫

文件：`Count-frontend/src/utils/company/sidebarCompanySwitch.js`

**Bank-only** = `hasBank && !hasGambling`（如纯 Bank 公司 CX / BK）。

允许的 Maintenance 路径：

- `capture-maintenance`
- `transaction-maintenance`
- `payment-maintenance`
- `formula-maintenance`
- `bankprocess-maintenance`

其他 Maintenance 路由或 Games 专属页：切到 bank-only 公司时可能 redirect 到 `dashboard`（见 `resolveMaintenanceRedirectForSession`）。

`useMaintenanceBankOnlyGuard`：Formula 等页在 bank-only 公司下 redirect（使用 `sessionHasTenant*` 判 category）。

---

## 6. Process 菜单 vs Bank Process Maintenance

| 概念 | 路由 | 说明 |
|------|------|------|
| **Bank Process List**（Process 权限） | `/bank-process-list` | 配置 BP、Accounting Due inbox |
| **Bank Process Maintenance** | `/bankprocess-maintenance` | 维护已入账 BP 交易行（软删等） |

Bank-only 登录时 Process 侧边栏指向 `bank-process-list`（非 `process-list`）。  
Bank Process **Maintenance** 仍在 Maintenance 子菜单下，需 **maintenance 权限 + tenant_has_bank**。

---

## 7. 关键文件索引

| 层 | 路径 |
|----|------|
| 侧边栏 UI | `Count-frontend/src/components/AuthenticatedLayout.jsx` |
| 权限 | `Count-frontend/src/utils/auth/sidebarPermissions.js` |
| Bank Process 显示 | `Count-frontend/src/utils/company/sharedCompanyFilter.js` → `shouldShowBankprocessMaintenanceInSidebar` |
| Session 读取 | `Count-frontend/src/utils/auth/sessionTenant.js` |
| 切公司 patch | `Count-frontend/src/utils/company/loginScope.js` → `patchMeFromCompanyContext` |
| Category flags | `Count-frontend/src/utils/company/companyCategoryFlags.js` |
| BP Maintenance 页守卫 | `Count-frontend/pages/maintenance/bankprocess/BankprocessMaintenancePage.jsx` |
| Bank-only redirect | `Count-frontend/src/utils/company/sidebarCompanySwitch.js` |

---

## 8. 变更检查清单

- [ ] 新增 Maintenance 子入口：是否更新 `AuthenticatedLayout` **与本文 §3**  
- [ ] 是否仍用 `tenant_has_*` / `sessionHasTenant*`，而非 `company_has_*`  
- [ ] Bank Process Maintenance 是否仍要求 **具体 Company**（非 Group-only）  
- [ ] Bank-only 公司是否仍走 `sidebarCompanySwitch` 允许路径  
- [ ] 修改 `SessionUser` 字段名时：同步 `sessionTenant.js` + 本文 §4  
- [ ] Maintenance 页是否 **不回归** Category pills（§9）

---

## 9. Category 筛选条（已移除）

2026-07-24 起，**所有 Maintenance 页面不再展示** 顶部 `Category:` pills（Games / Bank / Loan / Rate / Money）。

| 项 | 约定 |
|----|------|
| UI | 不渲染 `maintenance-permission-filter-header` |
| 仍走 PHP 的页 | Transaction / Formula / Capture 等仍在内部 **自动选择** category 传给旧 API；用户不可手动切换 |
| Spring Payment / Bank Process Maintenance | 仅用 `tenantId`，本就不依赖 Category pills |
| 公司能力 | 由 Group/Company pill + session `tenant_has_*` 决定 sidebar 入口，不再重复 Category 行 |

涉及文件：`PaymentMaintenancePage`、`TransactionMaintenancePage`、`FormulaMaintenancePage`、`BankprocessMaintenanceFilters.jsx`。

---

## 10. Transaction Maintenance 数据契约（Spring，已切换）

只读列表（无 delete），把 `Count-frontend/pages/maintenance/transaction/*` 原本打的旧 PHP `api/transactions/maintenance_search_api.php` 换成 Spring 接口。**当前进度：后端（Mapper/Dao/DTO/Service/Controller）+ 前端均已实现并切换。**

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/transaction/*`（页面/表格/筛选组件不改，字段名已对齐） |
| 数据源 | `data_capture_line`（一行 = 一条明细，MAIN+SUB 全展示，不筛 `product_type`） |
| 租户 | **一律 `tenantId`**，与 Payment/Bank Process Maintenance 同一原则：不再传 / 校验 `company_id`、`group_id`、`view_group`、`report_scope`、`group_aggregate` 等 scope 参数（旧前端那套跨公司聚合是纯前端循环单租户请求实现的，后端从未支持过） |
| Category | **必填**，`dc.category = #{category}` 是 WHERE 里的硬条件（不是 `<if>` 可选项）。Games/Gambling/Loan/Rate/Money → `GAME`，Bank → `BANK`；缺失或无法识别直接抛 `BusinessException`。这是为了防止 Select All（不选具体 process）时 GAME/BANK 数据混在一次响应里返回——见对话最初的要求：game/bank 展示不能串 |
| 删除 | 本次不做。页面本身**无删除入口**，仅为查看；`deleted`/`deletedBy`/`deletedAt` 暂固定回 `false`/`null`，等 Capture Maintenance 的软删归档表落地后再接 |
| 不含分页 | 跟 Payment/Bank Process Maintenance 一样，一个日期范围一次性查完；前端那套日期分片/分页/重试/流式 `onProgress`（服务旧 PHP 分页）后续要跟着简化掉 |

### 10.1 SQL / 字段来源

Mapper：`MaintenanceMapper.xml` → `findTransactionLineMaintenanceRows`（复用现有 `MaintenanceDao`，未新建 Dao/Mapper 文件）。

```sql
data_capture_line dl
INNER JOIN data_captures dc ON dc.id = dl.capture_id AND dc.tenant_id = dl.tenant_id
INNER JOIN process p        ON p.id  = dc.process_id
INNER JOIN currency c       ON c.id  = dl.currency_id AND c.tenant_id = dl.tenant_id
INNER JOIN account a        ON a.id  = dl.account_id
WHERE dl.tenant_id = #{tenantId}
  AND dc.capture_date BETWEEN #{dateFrom} AND #{dateTo}
  AND dc.category = #{category}  -- 必填，GAME/BANK 二选一
```

| JSON 字段 | 来源 | 备注 |
|-----------|------|------|
| `dtsCreated` | `data_capture_line.created_at` | 行自身时间戳（不是 header 的） |
| `process` | `process.code` | 经 `data_captures.process_id`；同一字段既是请求过滤参数也是响应列 |
| `idProduct` | `data_capture_line.id_product` | |
| `account` | `account.account_id` | 经 `data_capture_line.account_id` |
| `description` | `product_type='MAIN' ? description_main : description_sub` | 行自身快照，不取 header |
| `remark` | `data_captures.remark` | header 表单 remark，同一 capture 下所有行共用 |
| `percent` | `data_capture_line.source_percent` | |
| `currency` | `currency.code` | 经 `data_capture_line.currency_id`（行自己的币别，不是 header 的） |
| `rate` | `data_capture_line.rate_expression` | 原始 rate 文本 |
| `cr` / `dr` | `data_capture_line.processed_amount` 正负拆分 | 正数进 `cr`，负数取绝对值进 `dr`；**不 join `transactions`**，与 `DataCaptureSummaryServiceImpl.toTransaction()` 里 `finalAmount.signum()>0 ? WIN : LOSE` 的正负约定一致 |
| `createdBy` | `data_captures.created_by` | Submitter，header 字段（line 本身无 `created_by`） |

`process` 请求参数兼容传 code（Company 模式）或数字 id（Group 模式）：`UPPER(p.code)=UPPER(#{process}) OR CAST(p.id AS CHAR)=#{process}`。

`q` 搜索覆盖 `id_product` / `account.account_id` / `description_main` / `description_sub` / `data_captures.remark` / `data_captures.created_by`。

### 10.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceTransactionDTO.java`（请求字段 `tenantId`/`dateFrom`/`dateTo`/`process`/`category`/`q` 与响应列共用同一个类，风格对齐 `MaintenancePaymentDTO`） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findTransactionLineMaintenanceRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → `findTransactionLineMaintenanceRows` |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceTransactionsRows`（`TC_ROW_ORDER`：`dtsCreated` desc, `id` desc；`normalizeTransactionCategory` 做 Games/Gambling/Loan/Rate/Money→GAME、Bank→BANK 映射） |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/transaction-maintenance/list`（用户自行实现，已核对） |
| 前端 | `Count-frontend/.../transaction/transactionMaintenanceLogic.js`、`components/TransactionMaintenanceTable.jsx` |

### 10.3 前端改动（`transactionMaintenanceLogic.js`）

整份重写，对外导出的函数名/签名保持不变（`TransactionMaintenancePage.jsx` 未改一行）：

| 项 | 说明 |
|----|------|
| 请求目标 | `api/transactions/maintenance_search_api.php`（旧 PHP，分页）→ `POST api/maintenance/transaction-maintenance/list`（Spring，一次性返回整段日期范围） |
| 去掉 | 日期分片（`splitMaintenanceDateRange` 等）、分页游标/重试（`fetchAllPagesForRange`/`fetchMaintenancePageWithRetries`）、`appendMaintenanceScopeToParams`（不再传 `company_id`/`view_group`/`group_id`/`report_scope`/`group_only`/`group_aggregate`）、未被任何页面使用的 `packMaintenanceCache`/`getMaintenanceCacheRows`/`isMaintenanceCacheComplete`（已用 grep 确认全仓库无引用） |
| 新增 | `buildSpringTransactionMaintenanceRequest`（组请求体，`category` 缺失直接抛错）、`normalizeSpringTransactionMaintenanceRow`（camelCase → `dts_created`/`id_product`/... 表格字段）、`fetchTransactionMaintenanceOnce`（单租户单次 fetch）、`resolveTransactionMaintenanceTenantId`（从 `scope.scopeCompanyId ?? scope.uiCompanyId` 取 tenantId，取自 `report/shared/reportScope.js` 里 `scopeCompanyId` 字段，各 scope mode 通用） |
| 保留行为 | `scope.mode === "aggregate"`（Group 聚合视图）：仍是前端对 `mergeCompanyIds` 里每个公司循环单租户请求 + 合并排序，只是内层单次请求换成新接口；`onProgress` 在聚合模式下每查完一个公司回调一次，单公司模式下查完整段一次性回调 |
| 行 key 变化 | 旧行用 `transaction_id`/`capture_id`/`capture_detail_id` 三个 id；新行只有一个 `id`（= `data_capture_line.id`）。`TransactionMaintenanceTable.jsx` 的 `getItemKey` 同步从 `row.transaction_id` 改成 `row.id` |
| `q` 搜索 | 不传给后端，跟旧版一样纯前端 `filterTransactionMaintenanceRowsBySearch` 过滤（去掉了字段列表里已不存在的 `from_account`） |

### 10.3.1 Process 下拉修复（Company 模式抓不到当前公司 process）

`fetchProcessesForMaintenance`（本页 Process 下拉的数据源）原本就有三个分支，Bank category 分支（固定 `SALARY`/`BONUS`/`PROFIT`/`COMMISSION`）和 Group 分支都还在正常工作，**问题出在 Company 模式分支**：一直调用 `maintenanceCompanyApi.js` 里的 `fetchMaintenanceProcesses`，打的是未迁移的旧 PHP `api/processes/processlist_api.php`，导致下拉框空、连带搜索 "No data found"。

修复：Company 模式改调 `pages/processlist/processListApi.js` 的 `fetchProcessListByTenantId(tenantId)`——跟 Process List 页（`docs/process-list-spring-api.md`）同一个 `POST /api/process/process-list`，`normalizeProcessListRows` 已经把 `category === 'BANK'` 的行丢了，返回的就是当前 tenant 下**全部 GAME process**（不筛 status，含 INACTIVE，因为历史数据可能引用已停用的 process）。顺手删掉了这个分支下已经不可达的死代码（`payrollChannel` 在函数最上面已经 return 过一次，走到这里必为 false，原来的 `permForApi`/二次 payroll 过滤永远不会执行）。

范围只改了 `transactionMaintenanceLogic.js` 自己，没碰共享的 `maintenanceCompanyApi.js`——Capture/Payment/BankProcess Maintenance 的 Process 下拉如果有同样问题，需要另外处理，这次没有一并修。

### 10.3.2 Bank category 查询 "No data found" 修复（category 值传错）

**现象**：Games category 下查询正常；切到 Bank category（payroll-only 公司，如截图里的 OK2），选 SALARY 搜索却 "No data found"，但 `data_capture_line` 表里明明有对应数据。

**根因**：`data_captures.category` 完全由**提交时选中的 process 自己的 `process.category`** 决定（`DataCaptureSummaryServiceImpl.java:427,450` — `isGame = process.getCategory() != BANK`），跟公司是否 payroll-channel 无关；SALARY/BONUS/PROFIT/COMMISSION 属于 BANK 分类的 process，所以这类数据实际存的是 `category='BANK'`。但 `resolveTransactionMaintenanceCategory`（继承自旧 PHP 版本的逻辑）里有一段"payroll-channel/C168 公司选 Bank 时强制发 `category=Games`"的历史兼容代码——旧 PHP 系统里这几个 subsidiary 的 "category" 可能对应完全不同的表/查询，这段兼容跟新 Spring 端 `dc.category` 硬过滤（§10 表格里的必填约定）冲突：查询变成"找 category=GAME 的 SALARY"，实际数据是 BANK，自然查不到。

**排查方式记录**：一开始怀疑是 Process 下拉传值走 `id` 还是 `process.code` 的问题（Bank 分支固定列表 `id`/`process_name` 两个字段本来就是同一个字符串，这个假设不成立），改用 `DataCaptureSummaryServiceImpl` 源码反查 category 写入逻辑才定位到真正原因，不是字段值格式问题，是 category 语义传错。

**修复**：`resolveTransactionMaintenanceCategory` 去掉 payroll-channel/C168 的 Games 覆盖分支，`permission === "bank"` 现在无条件返回 `"Bank"`（→ 后端 `BANK`）。该函数 grep 确认全仓库只有 `searchTransactionData` 一处在用，改动无副作用；签名同步去掉不再需要的 `scope` 参数。

### 10.3.3 切公司 500（`switch-tenant` 用了 GET，接口只收 POST）

**现象**：在 Transaction Maintenance 页面切换 Company（如 OK1→OK2）时报 "Failed to update session company"，Network 里 `switch-tenant?tenant_id=53` 500。

**根因**（后端日志实锤，非推测）：

```
org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'GET' is not supported
```

`updateSessionCompany`（本页）发的是不带 `method` 的裸 `fetch()`，默认 GET；而 `AuthController.switchTenant` 是 `@PostMapping("/switch-tenant")`（[AuthController.java:107](../backend/src/main/java/com/eazycount/controller/AuthController.java)），只收 POST。跟同一次报告里 `dashboard_bootstrap_api.php` 的 500 是两回事——那个是巧合／另一个独立问题，不是同一根因。

**修复**：`transactionMaintenanceLogic.js` 的 `updateSessionCompany` 加上 `method: "POST"`。

**同款毛病还在别处，本次未修**：`grep switch-tenant` 发现 Payment / BankProcess / Capture / Formula Maintenance 四个页面各自的 `updateSessionCompany`，以及 `UserListPage.jsx`、`useMemberWinLoss.js`，写法跟这次改之前的 Transaction Maintenance 一模一样——同样是裸 `fetch()` 没带 `method: "POST"`。正确写法参考 `utils/auth/authApi.js` 的 `switchSessionTenant` / `utils/company/companySessionSync.js` 的 `syncCompanySessionApi`（都带了 `method: "POST"`）。这些页面切公司大概率会踩到同一个 405/500，需要的话应该是一次性统一补 `method: "POST"`，这次只动了 Transaction Maintenance 范围内的一处。

### 10.4 待办

- [x] Controller：`POST /api/maintenance/transaction-maintenance/list`（无 delete 端点）
- [x] 前端 `transactionMaintenanceLogic.js` 切到新接口，去掉日期分片/分页/重试逻辑与 scope 参数
- [x] `TransactionMaintenanceTable.jsx` 的 `getItemKey` 改用 `row.id`
- [x] Process 下拉 Company 模式改打 Spring `/api/process/process-list`（原来打旧 PHP 导致下拉是空的）
- [x] Bank category（payroll-only 公司）查询 "No data found" 修复：去掉 `resolveTransactionMaintenanceCategory` 里错误的 Bank→Games 覆盖
- [x] `updateSessionCompany` 切公司 500 修复：加 `method: "POST"`（仅本页；Payment/BankProcess/Capture/Formula Maintenance + UserListPage + useMemberWinLoss 有同款问题未修，见 §10.3.3）
- [ ] 实机验证：单公司、Group 聚合、Games/Bank 切换、Select All 不选 process 时不出现 GAME/BANK 混列、Process 下拉能列出当前公司全部 process、Bank category（含 payroll-only 公司）能查到 SALARY/BONUS/PROFIT/COMMISSION 数据、切公司不再 500
- [ ] 排查 Capture/Payment/BankProcess Maintenance 的 Process 下拉是否也在用 `maintenanceCompanyApi.js` 的 `fetchMaintenanceProcesses`（旧 PHP），有的话需要单独修
- [ ] 决定要不要把 §10.3.3 里其余 6 处 `switch-tenant` 缺 `method: "POST"` 的地方一并修掉
- [ ] Capture Maintenance 软删归档表（`data_capture_line_deleted`）落地后，回来把 `deleted`/`deletedBy`/`deletedAt` 接上 live+archived 合并查询（同 §5.6 Payment Maintenance 的模式）
