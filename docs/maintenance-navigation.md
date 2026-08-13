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
| 仍走 PHP 的页 | Capture 仍在内部 **自动选择** category 传给旧 API；用户不可手动切换。Transaction（§10）和 Formula（§11）的 List/Update/Delete 均已切 Spring，但仍是内部自动选 category，不回归 UI 选择器 |
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
- [x] ~~Capture Maintenance 软删归档表（`data_capture_line_deleted`）落地后，回来把 `deleted`/`deletedBy`/`deletedAt` 接上 live+archived 合并查询~~ —— 用户已确认不需要，Transaction Maintenance 只看活跃行即可（见 §13.4）

## 11. Formula Maintenance 数据契约（Spring，List + Edit + Delete 已切换）

把 `Count-frontend/pages/maintenance/formula/*` 原本打的旧 PHP（`list_api.php`/`update_api.php`/`delete_api.php`/`get_accounts_api.php`）全部换成 Spring 接口。**当前进度：List/Update/Delete 三个接口的后端（DTO/Dao/Mapper/Service/Controller）+ 前端均已实现并切换；账户下拉也已切到 Spring `/api/account/list`。**

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/formula/*`（表格/行组件 `FormulaMaintenanceTable.jsx`/`FormulaVirtualDataRow.jsx` 不改，字段名在前端归一化层已对齐） |
| 数据源 | `data_capture_formula`（Summary 列表 + Edit Formula Save + Formula Maintenance 共用的持久配置表；**不**是按天 capture，无日期范围参数） |
| 租户 | **一律 `tenantId`**，与 Transaction/Payment/Bank Process Maintenance 同一原则：不再传 / 校验 `company_id`、`group_id`、`view_group`、`report_scope`、`group_aggregate` 等 scope 参数 |
| Category | **必填（仅 List）**，`p.category = #{category}` 是 WHERE 里的硬条件（不是 `<if>` 可选项）。Games/Gambling/Loan/Rate/Money → `GAME`，Bank → `BANK`；缺失或无法识别直接抛 `BusinessException`。与 §10 Transaction Maintenance 同一套 `normalizeMaintenanceCategory`（原名 `normalizeTransactionCategory`，这次改成通用命名，两处复用同一实现）。Update/Delete 按 `id`/`formulaIds` + `tenantId` 直接定位行，不需要 category |
| 软删除 | **不存在**。`data_capture_formula` 表注释明确"硬删除；不绑定单次 data_captures"，Delete 是真正的 `DELETE FROM`，DTO 里也**没有** `deleted`/`deletedBy`/`deletedAt` 占位字段（这点跟 §10 Transaction Maintenance DTO 不同——那边为了跟其它 Maintenance 页字段对齐保留了恒为 `false`/`null` 的占位，Formula 场景没必要） |
| Edit 可编辑字段 | 仅 `account_id`/`source_percent`/`input_method`/`formula`/`description` 五个；`process`/`product_type`/`id_product`/`parent_id_product`/`formula_variant`/`sub_order`/`currency_id`/`source_columns`/`columns_display`/`formula_operators`/`enable_source_percent`/`enable_input_method`/`created_by`/`created_at` 全部只读，这次 Edit 不碰。详见 §11.4 |
| Delete | 硬删除、批量（`formulaIds: List<Integer>`），tenant 隔离。详见 §11.5 |

### 11.1 SQL / 字段来源

Mapper：`MaintenanceMapper.xml` → `findFormulaMaintenanceRows`（复用现有 `MaintenanceDao`，未新建 Dao/Mapper 文件）。

```sql
data_capture_formula f
INNER JOIN process p  ON p.id = f.process_id AND p.tenant_id = f.tenant_id
LEFT JOIN account a   ON a.id = f.account_id
LEFT JOIN currency c  ON c.id = f.currency_id AND c.tenant_id = f.tenant_id
WHERE f.tenant_id = #{tenantId}
  AND p.category = #{category}  -- 必填，GAME/BANK 二选一
ORDER BY f.id_product ASC, f.product_type ASC, f.sub_order ASC, f.formula_variant ASC, f.id ASC
```

`account`/`currency` 用 `LEFT JOIN`（不是 §10 Transaction 那种 `INNER JOIN`），因为 `data_capture_formula.account_id`/`currency_id` 允许为空。

| JSON 字段 | 来源 | 备注 |
|-----------|------|------|
| `process` | `process.code` | 经 `data_capture_formula.process_id`；同一字段既是请求过滤参数也是响应列，跟 §10 Transaction Maintenance 一致 |
| `productType` | `data_capture_formula.product_type` | `MAIN`/`SUB` |
| `idProduct` / `parentIdProduct` | 同名列 | SUB 行才有 `parentIdProduct` |
| `formulaVariant` / `subOrder` | 同名列 | 同一 `id_product` 多套公式 / SUB 排序；前端目前没有专门展示位 |
| `account` | `account.account_id` | 经 `data_capture_formula.account_id`，可能为 `null` |
| `currency` | `currency.code` | 经 `data_capture_formula.currency_id`，可能为 `null` |
| `description` | `data_capture_formula.description` | |
| `sourceColumns` / `columnsDisplay` | 同名列 | 前端目前未消费，仅透传 |
| `formula` | `data_capture_formula.formula` | **表格 Formula 列直接展示的就是这个字段**（原始表达式，如 `$3`、`$3-$2`、`$2*($3/$2-0.0025)`），不是 `formulaOperators`，也没有做旧版编辑框那种 `base + (source%)` 拼接展示 |
| `formulaOperators` | `data_capture_formula.formula_operators` | 表注释"公式运算符片段（可选）"；DTO 有这个字段但**前端未使用**，只是透传 |
| `inputMethod` | `data_capture_formula.input_method` | |
| `sourcePercent` / `enableSourcePercent` / `enableInputMethod` | 同名列 | `sourcePercent` 直接映射到表格 Source 列 |
| `createdBy` / `updatedBy` / `createdAt` / `updatedAt` | 同名列 | 前端表格目前**没有展示位**（跟 §10 Transaction Maintenance 不同，那边有 `created_by`/`dts_created`），DTO 里保留但未消费 |

`process` 请求参数兼容传 code（Bank 分支固定 `SALARY`/`BONUS`/`PROFIT`/`COMMISSION`）或数字 id（Games 分支）：`UPPER(p.code)=UPPER(#{process}) OR CAST(p.id AS CHAR)=#{process}`，写法跟 §10.1 Transaction Maintenance 一致。

`q` 搜索覆盖 `id_product` / `account.account_id` / `description` / `formula` / `created_by`。

### 11.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceFormulaDTO.java`（请求字段 `tenantId`/`process`/`category`/`q` 与响应列共用同一个类，风格对齐 `MaintenanceTransactionDTO`；无日期范围、无软删占位字段） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findFormulaMaintenanceRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → `findFormulaMaintenanceRows` |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceFormulaRows`（`parseFormulaListQuery` + `FormulaListQuery` record；category 走 `normalizeMaintenanceCategory`，与 Transaction Maintenance 共用同一方法，无额外行排序 comparator——SQL 的 `ORDER BY` 已经按 product 分组排好） |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/formula-maintenance/list` |
| 前端 | `Count-frontend/.../formula/formulaMaintenanceLogic.js`（表格/行组件未改） |

### 11.3 前端改动（`formulaMaintenanceLogic.js`）

对外导出的函数名/签名基本保持不变（`FormulaMaintenancePage.jsx` 未改一行 —— `listFormulaTemplates({ companyId, category, process, search, scope })` 入参签名照旧，内部实现整个换掉）：

| 项 | 说明 |
|----|------|
| 请求目标 | `api/formula_maintenance/list_api.php`（旧 PHP，GET + scope query 参数）→ `POST api/maintenance/formula-maintenance/list`（Spring，body 是 `{tenantId, process, category, q}`） |
| tenantId 解析 | 复用 `formulaMaintenanceScope.js` 里已有的 `formulaMaintenanceEffectiveCompanyId(scope, companyId)`（原本只给 update/delete 的旧 payload 用，这次接上了 list），取 `scope.scopeCompanyId` 回退 `uiCompanyId` |
| 新增 | `resolveFormulaMaintenanceCategory`（Loan/Rate/Money/Gambling → Games，逻辑照抄 §10.3 `transactionMaintenanceLogic.js` 的 `resolveTransactionMaintenanceCategory`）、`buildSpringFormulaMaintenanceRequest`（组请求体，`tenantId`/`category` 缺失直接抛错）、`normalizeSpringFormulaMaintenanceRow`（camelCase DTO → 表格已在用的旧字段名 `process`/`account`/`currency`/`source`/`product`/`input_method`/`formula`/`description`） |
| Process 下拉 | `fetchProcesses` 的 Company/Games 分支从旧 PHP `fetchMaintenanceProcesses`（`maintenanceCompanyApi.js`）换成 `fetchProcessListByTenantId`（`processlist/processListApi.js`），跟 §10.3.1 Transaction Maintenance 的修复对称；Bank 硬编码四选项分支和 Group 分支未改动 |
| 去掉 | `appendFormulaScopeToParams` 不再用于 list（仍保留给 `fetchAccounts` 用，因为账户接口本次未迁移） |
| 未改动 | `fetchAccounts`/`updateFormulaTemplate`/`deleteFormulaTemplates`——仍打旧 PHP，本次范围只做 List |
| Product 列展示 | `resolveFormulaProductDisplay`：MAIN 行展示自己的 `idProduct`；SUB 行展示 `parentIdProduct`（不是 `parent / child` 拼接——最初实现拼接过，用户反馈后改成只显示 parent） |

### 11.3.1 Bank category 下 Process 下拉空白修复（`handleClearCompany` 漏传 permission）

**现象**：Group-only（点掉 Company，只剩 Group ID pill）+ 该 group 的 category 是 Bank 时，Process 下拉只有 "Select All"，没有 `PROFIT`/`SALARY`/`COMMISSION`/`BONUS` 四个固定选项，表格也一直是 "Select process" 空态。

**根因**：`FormulaMaintenancePage.jsx` 里一共 5 处调用 `fetchProcesses(companyId, scope, permission)`，`handleClearCompany`（点掉 Company 回到 Group-only 状态那个 handler）是唯一一处只传了两个参数、漏了 `permission`：

```js
const procList = scope ? await fetchProcesses(null, scope) : [];  // 漏了第三个参数
```

`fetchProcesses` 内部判断顺序是先看 `permission === "bank"`（或 `scope.c168Channel`/`companyPayrollChannel`）直接返回硬编码四选项，没命中才会走 `formulaMaintenanceUsesGroupProcesses(scope)` 的 Group 分支（打 `fetchDomainReportProcesses`，这条路径只认 Games 域的 process，不认 Bank）。`permission` 传空字符串时第一个判断必然落空，于是纯 Bank 分类的 group（没打 `c168Channel`/`companyPayrollChannel` 标记的那种）就会一路走到 Group 分支，`fetchDomainReportProcesses` 自然查不到东西，返回空数组。

**修复**：跟其余 4 处调用（如 `bootstrapFormulaMaintenanceMeta` 之后紧跟的 `fetchProcesses(null, bootScope, meta.activePermission)`）保持一致，补上 `meta.activePermission`：

```js
const meta = await bootstrapFormulaMaintenanceMeta({ companies, groupId: g });
const procList = scope ? await fetchProcesses(null, scope, meta.activePermission) : [];
```

只改了 `FormulaMaintenancePage.jsx` 这一处调用点，`formulaMaintenanceLogic.js` 未动。

**这个修复只是治标**：§11.3.1 修好之后 Bank category 下拉仍然可能空（用户反馈"依旧没有正常展示"），往下查才发现真正的病根在 §11.3.2。

### 11.3.2 Bank category 下拉仍然空白：`activePermission` 本身就解析错了

**现象**：§11.3.1 修完、`meta.activePermission` 已经正确传进 `fetchProcesses` 之后，Bank-only 公司/group 的 Process 下拉**依然**只有 "Select All"。

**根因**：问题不在"漏传"，而在传的值本身就是错的。`pickFormulaMaintenancePermission(permissions, saved)` 在没有 `saved`（localStorage 里没记录过上次选的 category）时永远取 `permissions[0]`。而 `permissions` 来自 `fetchCompanyPermissions`/`fetchCompanyPermissionsRaw` → `fetchDomainCompanyPermissions`（`maintenanceCompanyApi.js`）→ 打的是旧 PHP `POST api/domain/domain_api.php`——这个接口在反向代理把所有 `/api/*` 转发给 Spring 后必然 500（Spring 从未实现过这条路由），`fetchDomainCompanyPermissions` 的 catch-all 兜底返回的是 `DEFAULT_PERMISSIONS_FORMULA = ["Games", "Bank", "Loan", "Rate", "Money"]`——**Games 排第一个**。于是对一个真正的 Bank-only 公司，`activePermission` 被静默地错误解析成 `"Games"`，`fetchProcesses` 里 `permission === "bank"` 的判断自然不成立；如果这个公司/group 又没有被打上 `scope.c168Channel`/`companyPayrollChannel` 标记（这两个标记的判定跟"category 到底是不是 Bank"是两套独立逻辑，不能互相兜底），就会一路错误地走进 `formulaMaintenanceUsesGroupProcesses` 的 Group 域分支，查出来自然是空的。

这跟 §10.3.2 Transaction Maintenance 那次 "No data found" 是**同一个底层病因**（同一个 `domain_api.php` 500），但触发路径不同：Transaction 那次是 category 传值语义反了（Bank 传成了 Games），这次 Formula 是 category 从一开始就没解析对。

**修复**：新增 `resolveFormulaMaintenanceActivePermission(permissions, saved, scope)`（`formulaMaintenanceLogic.js`），逻辑照抄 Transaction Maintenance 已经验证过的 `resolveTransactionMaintenanceActivePermission`——`scope.c168Channel`/`scope.companyPayrollChannel` 为真时无条件返回 `"Bank"`，否则才走原来的 `pickFormulaMaintenancePermission`。`FormulaMaintenancePage.jsx` 里三处原本调 `pickFormulaMaintenancePermission(perms, saved)` 算 `activePermission` 的地方全部换成这个新函数（并传入当时能拿到的 scope）：

| 位置 | 改动 |
|------|------|
| 初次进页面、已选中具体 Company 的 boot 分支 | `pickFormulaMaintenancePermission(permList, savedPerm)` → `resolveFormulaMaintenanceActivePermission(permList, savedPerm, bootScope)` |
| 切公司/切 Group 后触发的 meta-effect | 同上，用闭包里现成的 `scope` |
| `handleSwitchCompany`（点击某个公司 pill 直接切换） | 同上；顺手把 `nextScope` 的计算挪到 `nextActive` 之前（原来 `nextActive` 算的时候 `nextScope` 还没算出来，没法传） |

**没改**（跟 Transaction Maintenance 的参考实现保持一致）：Group-only（没选具体 Company）时的两处 boot 分支——`bootstrapFormulaMaintenanceMeta` 内部的 `pickFormulaMaintenancePermission` 调用、以及调用方直接用 `meta.activePermission` 的地方——**没有**套这层 override。这是照抄 §10 Transaction Maintenance 自己的写法：Group-only 场景下这几处本来就不做 permission 覆盖，是否命中 Bank 硬编码列表完全依赖 `fetchProcesses` 里 `scope.c168Channel`/`companyPayrollChannel` 那两个 flag 是否被 `resolveFormulaMaintenanceScope` 正确标记。

**这次修复也没能根治**：用户继续反馈"依旧展示不出 Bank 格式 process"，往下查才发现问题不在 §11.3.1/§11.3.2 改的任何一处，而在这两个 flag 从源头上就没被打上——见 §11.3.3。

### 11.3.3 真正病根：`resolveFormulaMaintenanceScope` 从来没设置过 `c168Channel`/`companyPayrollChannel`

**现象**：§11.3.1、§11.3.2 都修完、代码逻辑也复核过没问题，Bank-only 公司的 Process 下拉**仍然**只有 "Select All"——不只是 Group-only，具体选中某个 Bank-only Company 也一样。

**根因**：`formulaMaintenanceScope.js` 里的 `resolveFormulaMaintenanceScope` 一直是这样写的：

```js
export {
  resolveCustomerReportScope as resolveFormulaMaintenanceScope,
};
```

**纯粹的裸重导出**，直接把 Games-only 的 Customer Report 页面用的 `resolveCustomerReportScope` 原样当成 Formula Maintenance 自己的 scope 解析函数，没有任何加工。而 `resolveCustomerReportScope`（`report/shared/reportScope.js`）本身**完全不认识** `c168Channel`/`companyPayrollChannel` 这两个字段，从来没往返回的 scope 对象里塞过它们——这两个字段名只在 Maintenance 系列文件（`fetchProcesses`、`formulaMaintenanceUsesGroupProcesses`、`formulaMaintenanceScopeApiParams`，以及这次新增的 `resolveFormulaMaintenanceActivePermission`）里被**读取**，从未被**写入**。也就是说，从这个页面上线的第一天起，`scope.c168Channel`/`scope.companyPayrollChannel` 在 Formula Maintenance 里必然恒为 `undefined`，§11.3.1/§11.3.2 里所有依赖这两个 flag 的分支全部是死代码，从未真正执行过。

对照 Transaction Maintenance 的 `transactionMaintenanceScope.js`，会发现它的 `resolveTransactionMaintenanceScope` **不是**裸重导出，而是包了一层，用 `args.companyId` 反查 `args.companies` 里对应的公司行，再用 `isC168CompanyRow`/`isBankOnlyCompanyRow`（`utils/company/c168CaptureChannel.js`）算出这两个 flag 补进 scope：

```js
export function resolveTransactionMaintenanceScope(args) {
  const base = resolveCustomerReportScope(args);
  if (!base) return base;
  const cid = args?.companyId != null ? Number(args.companyId) : Number.NaN;
  const row = Number.isFinite(cid) && cid > 0
    ? (args?.companies ?? []).find((c) => Number(c.id) === cid)
    : null;
  const c168Channel = Boolean(row && isC168CompanyRow(row));
  const companyPayrollChannel = Boolean(row && (c168Channel || isBankOnlyCompanyRow(row)));
  return { ...base, c168Channel, companyPayrollChannel };
}
```

Formula Maintenance 这一层"enrich"从一开始就没写——大概率是当初从 Transaction Maintenance 抄这一整套 Maintenance 模式过来时，`*Scope.js` 这一个文件漏抄了这段包装逻辑，直接留了裸重导出。

**修复**：`formulaMaintenanceScope.js` 的 `resolveFormulaMaintenanceScope` 改成跟 `resolveTransactionMaintenanceScope` 一模一样的包装函数（同样从 `c168CaptureChannel.js` 引入 `isC168CompanyRow`/`isBankOnlyCompanyRow`）。`formulaMaintenanceUsesGroupProcesses`/`formulaMaintenanceScopeApiParams` 两个函数本身没动——它们的 `scope.c168Channel`/`companyPayrollChannel` 判断逻辑一直是对的，只是从来收不到真正的值。

修完之后这条链路才算真正打通：`resolveFormulaMaintenanceScope` 算出 `companyPayrollChannel: true` → `fetchProcesses` 第一个 `if` 命中 → 返回硬编码 `PROFIT/SALARY/COMMISSION/BONUS`；同时 `resolveFormulaMaintenanceActivePermission`（§11.3.2）也终于能命中它的 override 分支，`activePermission` 正确解析成 `"Bank"`。

**教训**：§11.3.1、§11.3.2 两次修复本身逻辑都没错，只是建立在"scope 上那两个 flag 是有值的"这个错误假设上——下次遇到"改了调用点/改了判断逻辑但现象不变"，应该先用 `console.log`/断点确认被读取的字段到底有没有被写入过，而不是继续在读取端加更多分支。

### 11.4 Edit（Update）API

#### 11.4.1 SQL

Mapper：`MaintenanceMapper.xml` → `updateFormulaMaintenanceRow`：

```sql
UPDATE data_capture_formula
SET source_percent = #{sourcePercent},
    input_method = #{inputMethod},
    formula = #{formula},
    description = #{description},
    account_id = #{accountId},
    updated_by = #{updatedBy}
WHERE id = #{id}
  AND tenant_id = #{tenantId}
```

- 只 `SET` 这五列——枚举字段/分类字段/`source_columns`/`formula_operators`/`enable_source_percent`/`enable_input_method`/`created_by`/`created_at` 全部不动，这是用户明确定下的规则："写了的字段允许 edit，没写的不允许"。
- `updated_at` **不手动传**，表定义本身 `ON UPDATE CURRENT_TIMESTAMP`，任何字段变更都会自动刷新；手动传只是多余的 Service 层工作量（讨论后用户选择让 MySQL 自动处理）。
- `enable_source_percent`/`enable_input_method` 这两个派生标志**保持原值不动**，不随 `source_percent`/`input_method` 的编辑自动重算（讨论后用户明确选择"保持不变"，不是我们最初提议的"自动重算"方案）。

草稿阶段修过的两个真 bug（供以后类似 SQL 复查参考）：
1. 全角中文逗号 `，` 混进 `SET` 子句，MySQL/MyBatis 不认，直接语法错误。
2. `WHERE` 子句最初没有 `tenant_id` 限定，是个跨租户越权更新的漏洞——照抄本文件其它所有 update/delete 语句都带 `tenant_id` 的惯例补上了。

#### 11.4.2 DTO / Dao / Service / Controller

| 层 | 内容 |
|----|------|
| DTO | `MaintenanceFormulaDTO` 新增 `accountId`（Integer，nullable，update 请求专用，同时也复用为 List 响应列——见 §11.6.1） |
| Dao | `MaintenanceDao.updateFormulaMaintenanceRow(tenantId, id, accountId, sourcePercent, inputMethod, formula, description, updatedBy)` |
| Service | `MaintenanceServiceImpl.updateFormulaMaintenance(ft)`：`requireWritableSession()`（登录 + 非只读，跟 Payment/BankProcess 的 delete 一个套路）→ 校验 `tenantId`/`id`（`requireFormulaTenantId`/`requireFormulaId`）→ `updatedBy` 取 session 的 `login_id`（不接受前端传值）→ `sourcePercent` 走 `normalizeSourcePercent`：空值兜底成 `"0"`（该列是 `NOT NULL DEFAULT '0'`，直接传 null 会撞 DB 约束）；`inputMethod`/`formula`/`description` 复用既有 `normalizeQ`（trim 后空串转 null，这几列本身允许 NULL）。0 行受影响时抛 `BusinessException("Formula maintenance record not found")` |
| Controller | `POST /api/maintenance/formula-maintenance/update`，body 是 `MaintenanceFormulaDTO`，响应 `data` 恒为 `null`（跟 Payment/BankProcess 的 delete 端点一致的"操作类接口不回数据"风格） |

#### 11.4.3 前端（`formulaMaintenanceLogic.js`）

| 项 | 说明 |
|----|------|
| 端点 | `api/formula_maintenance/update_api.php`（旧 PHP）→ `POST api/maintenance/formula-maintenance/update` |
| 请求体 | `buildSpringFormulaMaintenanceUpdateRequest({tenantId, id, accountId, sourcePercent, inputMethod, formula, description})`，`tenantId`/`id` 缺失直接抛错 |
| tenantId 解析 | 跟 List 一样用 `formulaMaintenanceEffectiveCompanyId(scope, companyId)`，调用点 `FormulaMaintenancePage.jsx` 的 `handleSaveRow` 不再手搓 `template_id`/`company_id` 那套旧 payload |
| **行为修正（重要）** | 老版本 Formula 列展示是"base 公式 + `*(source)`"拼接出来的派生显示串，编辑框里改 Source % 会连带重写 Formula 文本框内容（`syncEditFormSourcePercent` 原来会顺手改 `formula`）。但新后端 `formula`/`source_percent` 是两个独立列，各自展示各自存（§11.1 已确认 Formula 列展示的就是原始 `formula` 字段，不是拼接串）。继续用旧逻辑会把一段 `*(0.75)` 文本永久写死进 `formula` 字段存进 DB，数据会被污染。所以把这个副作用去掉了：现在改 Source % 只改 `source_percent`，Formula 框保持用户自己输入的原始文本，两者互不干扰 |
| `patchFormulaRowAfterSave` | 去掉了 `serverData` 参数——新后端 update 成功后 `data` 恒为 `null`，没有服务器回填字段；改成保存成功后直接把 `editForm` 里的值乐观地贴回本地行 |
| `createFormulaEditFormFromRow` | 去掉了 `source_ref`（对应旧的 `source_columns`，这次 Edit 范围明确不可编辑，UI 上也从来没有对应输入控件，纯粹是老代码的死重量） |
| 删掉的死函数 | `buildEditFormFormulaDisplay`/`resolveFormulaBaseFromRow`/`parseFormulaEditTail`/`buildFormulaEditString`——全仓库 grep 确认除定义处外无其它引用，安全删除 |

### 11.5 Delete API

#### 11.5.1 SQL

Mapper：`MaintenanceMapper.xml` → `deleteFormulaMaintenanceRows`：

```sql
DELETE FROM data_capture_formula
WHERE tenant_id = #{tenantId}
  AND id IN
  <foreach collection="ids" item="id" open="(" separator="," close=")">
      #{id}
  </foreach>
```

真正的硬删除，**不进任何归档表**（`data_capture_formula` 本来就没有对应的 `_deleted` 表，跟 §5/§6 Payment/Bank Process Maintenance 的软删模式完全不同——那两个是先 `INSERT...SELECT` 进 `transactions_deleted` 再删主表，Formula 这里就是一条 `DELETE`）。批量而不是单条：跟表格 UI"每行 checkbox + 顶部 Delete 按钮"的多选交互对齐，也对齐 `deleteByIdsAndTenantId`（Payment/BankProcess 用的那个）的写法。草稿阶段最初是单条 `id = #{id}` 且没有 `tenant_id` 限定，讨论后改成了批量 + tenant 隔离。

#### 11.5.2 DTO / Dao / Service / Controller

| 层 | 内容 |
|----|------|
| DTO | `MaintenanceFormulaDTO` 新增 `formulaIds`（`List<Integer>`，delete 请求专用；命名对齐 `MaintenancePaymentDTO`/`MaintenanceBankProcessDTO` 的 `transactionIds` 风格） |
| Dao | `MaintenanceDao.deleteFormulaMaintenanceRows(tenantId, ids)` |
| Service | `MaintenanceServiceImpl.deleteFormulaMaintenance(ft)`：`requireWritableSession()` → 校验 `tenantId` → `requireFormulaIds` 拿非空 id 列表 → 调 Dao 批量硬删；0 行受影响抛 `BusinessException("No matching formula maintenance records to delete")`。跟 Payment/BankProcess 的删除不一样：没有 archive 步骤，也没有 RATE/bank-process 那种级联展开逻辑 |
| Controller | `POST /api/maintenance/formula-maintenance/delete`，body 是 `MaintenanceFormulaDTO`，响应 `data` 恒为 `null` |

#### 11.5.3 前端

| 项 | 说明 |
|----|------|
| 端点 | `api/formula_maintenance/delete_api.php`（旧 PHP）→ `POST api/maintenance/formula-maintenance/delete` |
| 请求体 | `deleteFormulaTemplates({tenantId, formulaIds})`，内部校验非空 id 数组，`tenantId` 用 `formulaMaintenanceEffectiveCompanyId(scope, companyId)` 算 |
| 调用点 | `FormulaMaintenancePage.jsx` 的 `handleConfirmDelete`，不再传 `effectiveCompanyId`/`scope` 两个参数给 `deleteFormulaTemplates`，改传 `{tenantId, formulaIds: idsToDelete}` |

### 11.6 账户下拉两处修复

#### 11.6.1 List 响应补 `accountId`（数值 FK），Edit 才能正确回显选中账户

**问题**：List 的 `findFormulaMaintenanceRows` 原本只 `SELECT a.account_id AS account`（账户业务码字符串，如 `OK`），没有 `data_capture_formula.account_id` 本身（数值 FK）。点 Edit 时账户下拉的 `<option value={acc.id}>` 用的是数值 id，没有这个字段就没法正确预选中当前行的账户。

**修复**：`MaintenanceMapper.xml` 的 `findFormulaMaintenanceRows` SELECT 里加一行 `f.account_id AS accountId`，复用 `MaintenanceFormulaDTO` 已有的 `accountId` 字段（原本只给 update 请求用）——跟 `process` 字段"请求参数 + 响应列复用同一个字段"是同一个惯例。这是在"只改前端"的那一轮里顺手做的最小后端补丁（一行 SELECT，没碰 Dao 签名/Service/Controller），当时已明确告知用户。

前端 `normalizeSpringFormulaMaintenanceRow` 相应加了 `account_id: row.accountId ?? null`，`createFormulaEditFormFromRow` 直接读 `row.account_id` 回填编辑框。

#### 11.6.2 账户下拉本身是空的：`fetchAccounts` 迁移到 Spring `/api/account/list`

**现象**：点 Edit，账户下拉框只有 "Select Account" 占位，没有任何账户选项。

**根因**：`fetchAccounts` 跟 List/Update/Delete 迁移前的其它函数一样，还打着旧 PHP `api/transactions/get_accounts_api.php`——反向代理把所有 `/api/*` 转发给 Spring 后，这条没实现过的路由必然 500。

**修复**：改用 `pages/account/accountListApi.js` 的 `fetchAccountListByTenantId(tenantId)`（`POST /api/account/list`，Spring，tenant-scoped）+ `filterAccountListRows(rows)`（默认只留 active 账户，跟旧接口 `status=active` 参数行为一致）。这个 Spring 账户接口不是新写的，是复用现成的——Data Capture Summary 的 Edit Formula 账户下拉（`datacapturesummary/lib/summaryApi.js`）、Transaction 页的 `AccountSelect`（`transaction/lib/transactionAccountHelpers.js`）都已经在用。新增 `normalizeFormulaAccountOption`，把 Spring 返回的 `{id, account_id, name}` 拼成表格下拉需要的 `{id, account_id, display_text}`，`display_text` 格式 `"CODE (Name)"` 照抄 `transactionAccountHelpers.js` 的 `normalizeTransactionAccountOption`，保持全仓库账户下拉文案风格一致。

`fetchAccounts(companyId, scope)` 函数签名没变（内部改成 `formulaMaintenanceEffectiveCompanyId(scope, companyId)` 解析 `tenantId`），`FormulaMaintenancePage.jsx` 里 4 处调用点不用改。顺手删掉了只服务这条旧路径的 `appendFormulaScopeToParams` 函数和不再需要的 `formulaMaintenanceScopeApiParams` 导入。

### 11.7 待办

- [x] DTO/Dao/Mapper：`MaintenanceFormulaDTO` + `findFormulaMaintenanceRows`（无日期范围、LEFT JOIN account/currency）
- [x] Service：`findMaintenanceFormulaRows` + `parseFormulaListQuery`；`normalizeTransactionCategory` 改名 `normalizeMaintenanceCategory` 供两个 Maintenance 共用
- [x] Controller：`POST /api/maintenance/formula-maintenance/list`
- [x] 前端 `listFormulaTemplates` 切到新接口，`fetchProcesses` Company 分支改打 Spring process-list
- [x] Product 列 SUB 行展示改为只显示 `parentIdProduct`
- [x] Bank category 下 Process 下拉空白修复（漏传 permission）：`handleClearCompany` 补上 `meta.activePermission`（见 §11.3.1）
- [x] Bank category 下 Process 下拉空白修复（activePermission 解析本身就错）：新增 `resolveFormulaMaintenanceActivePermission`，三处 boot/switch 分支换用它（见 §11.3.2）
- [x] Bank category 下 Process 下拉空白修复（真正病根）：`resolveFormulaMaintenanceScope` 从裸重导出改成跟 Transaction Maintenance 一样会补 `c168Channel`/`companyPayrollChannel` 的包装函数（见 §11.3.3）
- [x] Update API：DTO（`accountId`）/Dao（`updateFormulaMaintenanceRow`）/Mapper/Service（`updateFormulaMaintenance` + `normalizeSourcePercent`）/Controller（`POST .../update`）+ 前端 `updateFormulaTemplate`（见 §11.4）
- [x] Delete API：DTO（`formulaIds`）/Dao（`deleteFormulaMaintenanceRows`，批量硬删）/Mapper/Service（`deleteFormulaMaintenance`）/Controller（`POST .../delete`）+ 前端 `deleteFormulaTemplates`（见 §11.5）
- [x] 编辑逻辑修正：Source % 编辑不再重写 Formula 文本框（旧的 base+"*(source)" 拼接展示逻辑已废弃，避免污染存库的 `formula` 字段），删掉四个相关死函数（见 §11.4.3）
- [x] List 响应补 `accountId`（数值 FK），Edit 账户下拉能正确回显当前行选中的账户（见 §11.6.1）
- [x] `fetchAccounts` 迁移到 Spring `POST /api/account/list`（原来打旧 PHP 导致账户下拉是空的，见 §11.6.2）
- [ ] `formulaVariant`/`subOrder`/`createdBy`/`updatedBy`/`createdAt`/`updatedAt`/`formulaOperators`/`sourceColumns`/`columnsDisplay` 目前 DTO 有但前端未消费，如果后续要展示需要加表格列
- [x] `updateSessionCompany` 切公司 500 修复（同 §10.3.3 根因）：`formulaMaintenanceLogic.js` 的 `updateSessionCompany` 裸 `fetch()` 默认 GET，`AuthController.switchTenant` 只收 POST；补上 `method: "POST"`
- [ ] 实机验证：Games/Bank 切换、Select All 不选 process 时不出现 GAME/BANK 混列、Process 下拉能列出当前公司全部 process、Bank category（SALARY/BONUS/PROFIT/COMMISSION）能查到数据、MAIN/SUB 行的 Product 列展示符合预期、Edit 保存后本地行正确刷新、Delete 批量勾选后确实从 DB 消失（硬删，刷新页面也不会再出现）

## 12. Capture Maintenance 数据契约（Spring，List + Delete 全链路已切换，行粒度为 capture）

只读列表。**行粒度改过一次**：最初做的是"一行 = 一条 `data_capture_line`"（一个 capture 里每个 Product 各占一行），用户看实机效果后纠正为"一行 = 一条 `data_captures`"（一个 capture/一次提交只占一行，不展开到 Product 明细），已经按新粒度重做完。跟 §10 Transaction Maintenance 同一套表、同一个 `MaintenanceDao`/`MaintenanceMapper.xml`（未新建 Dao/Mapper 文件）。**当前进度：DTO + Dao + Mapper SQL + Service + Controller + 前端全部按新粒度对齐完成；Delete 走 Spring（新粒度）。**

### 12.1 字段来源（capture 级别，已重做）

| JSON 字段 | 来源 | 备注 |
|-----------|------|------|
| `id` | `data_captures.id` | 行 key；**不再是** `data_capture_line.id` |
| `dtsCreated` | `data_captures.created_at` | header 级别，不再取某一行的 |
| `process` | `process.code`（经 `data_captures.process_id`） | 同一字段既是请求过滤参数也是响应列，跟 §10 一致 |
| `currency` | `currency.code`（经 **`data_captures.currency_id`**，不是某一行的） | |
| `product` / `wlGroup`（两列同一个表达式） | `CASE WHEN p.category='BANK' THEN p.code WHEN 有选 description THEN 逗号拼接的 description 名 ELSE p.code END` | BANK 直接用 process code（本来就是 SALARY/BONUS/PROFIT/COMMISSION）；GAME 取 `data_capture_description` 关联的 `process_description.name`，多选逗号拼接，**没选任何 description 时回退显示 process code**（用户确认，已用真实数据验证：3 条测试 capture 都没选过 description，回退后显示 "SALARY"/"BONUS"，不会出现空白格） |
| `createdBy` | `data_captures.created_by` | header 字段，不变 |
| `deleted`/`deletedBy`/`deletedAt` | 活跃行固定 `false`/`null`/`null`；归档行来自 `data_capture_line_deleted` 按 `capture_id` 分组，`deletedBy`/`deletedAt` 用 `MAX()` 聚合（同一 capture 底下所有行是同一次批量归档写入的，值本来就一样，`MAX` 只是满足 GROUP BY 语法，不是真的在聚合不同的值） | |

SQL 结构：`FROM data_capture_line dl`（或归档行是 `data_capture_line_deleted dld`）`JOIN data_captures dc ...`，**`GROUP BY dc.id`** 把同一 capture 下的多条 line 折叠成一行——`dc.id` 是主键，其余选出来的字段都跟它函数依赖，不用把所有字段塞进 GROUP BY。`process`/`q` 两个 `<if>` 过滤条件的 SQL 片段基本没变，只是 `q` 不再搜 `id_product`（Product 列已经不来自它了），改成搜 `p.code` / `desc_agg.description_names` / `c.code`。两条 `<sql>` 片段（`captureDescriptionAgg` 子查询、`captureProductExpr` CASE 表达式）活跃/归档两条 SELECT 共用，没有重复写两遍。

`category` 必填硬过滤（`dc.category = #{category}`，不是 `<if>`），跟 §10 同样的教训——防止 Select All 不选 process 时 GAME/BANK 混列。

`process` 请求参数兼容 code 或数字 id：`UPPER(p.code)=UPPER(#{process}) OR CAST(p.id AS CHAR)=#{process}`。

### 12.1.1 连带简化：删除不用再"选中行反查 capture"了

因为列表本身已经是一行一个 capture，前端勾选的行 id 直接就是 `data_captures.id`，**`findCaptureIdsByLineIdsAndTenantId` 这个中间转换方法整个删掉了**，`MaintenanceCaptureDTO.lineIds` 也改名成 `captureIds`（不再需要"选中的 line id → distinct capture_id"这一步）。`deleteMaintenanceCaptureRows` 现在直接拿 `mc.captureIds` 进级联（归档 line → 联动归档/硬删 transaction → 硬删 line → 删 process_submitted），比上一版少一次查询往返。

### 12.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceCaptureDTO.java`（请求字段 `tenantId`/`dateFrom`/`dateTo`/`process`/`category`/`q`/`captureIds`(删除用) 与响应列共用同一个类，风格对齐 `MaintenanceTransactionDTO`） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findCaptureLineMaintenanceRows`/`findCaptureLineMaintenanceDeletedRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → 同名两条 SQL + `captureDescriptionAgg`/`captureProductExpr` 两个共用 `<sql>` 片段 |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceCaptureRows`（`CC_ROW_ORDER`：`dtsCreated` desc, `id` desc；复用 §11 的 `normalizeMaintenanceCategory`）+ `deleteMaintenanceCaptureRows` |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/capture-maintenance/{list,delete}`（用户自行实现，已核对） |
| 前端 | `Count-frontend/.../capture/captureMaintenanceLogic.js`（`CaptureMaintenancePage.jsx`/`CaptureVirtualRows.jsx`/`CaptureVirtualDataRow.jsx` 均未改一行——`row.capture_id` 字段名从一开始就是通用命名，现在后端 `id` 语义变成 capture 级别之后天然对得上，不用改前端展示层） |

### 12.3 前端改动（`captureMaintenanceLogic.js`）

导出的函数名/签名不变；只重写了内部实现：

| 项 | 说明 |
|----|------|
| `searchCaptureData` | `api/capture_maintenance/search_api.php`（旧 PHP）→ `POST api/maintenance/capture-maintenance/list`（Spring）。请求体只有 `{tenantId, dateFrom, dateTo, process, category, q}`，不再传 `company_id`/`view_group`/`group_id`/`report_scope`/`group_only`/`group_aggregate` |
| `normalizeSpringCaptureMaintenanceRow` | camelCase → 表格字段：`capture_id`（**现在 = `data_captures.id`**，行粒度改过之后语义变了，见 §12.1；字段名 `row.capture_id` 从一开始就是通用命名，页面选中/删除逻辑不用改）、`dts_created`、`product`、`process`、`currency`、`wl_group`、`submitted_by`、`is_deleted`、`deleted_by`、`dts_deleted` |
| **`category` 怎么定的（⚠️ 推断，需要实机验证）** | 本页**没有** Transaction Maintenance 那种 `activePermission` 状态，`searchCaptureData` 调用点也从没传过 `category`。新写的 `resolveCaptureMaintenanceCategory(scope)` 复用了 `fetchProcesses` 里本来就有的判断：`scope.c168Channel \|\| scope.companyPayrollChannel` → `"Bank"`，否则 `"Games"`——这跟"该用固定 4 个 Bank process 还是查真实 process 表"是同一个条件，不是凭空猜的，但**没有另外的信号源交叉验证过**，如果某个公司同时有 Game 权限又需要看 Bank 分类数据，这个判断会不够用，需要实测确认 |
| `fetchProcesses`（Company 模式） | 同 §10.3.1 的修复：从旧 PHP `fetchMaintenanceProcesses` 改打 Spring `fetchProcessListByTenantId`（`/api/process/process-list`） |
| `updateSessionCompany` | 补 `method: "POST"`（同 §10.3.3 根因，本页 `updateSessionCompany` 之前也是裸 `fetch()`） |
| `deleteCaptureItems` | 已切到 `POST api/maintenance/capture-maintenance/delete`，请求体 `{tenantId, captureIds}`（字段名跟着后端 `MaintenanceCaptureDTO.captureIds` 改的，之前叫 `lineIds`）。`row.capture_id` 现在就是 `data_captures.id`，勾选哪几行就直接把那几个 id 传过去，不用再转换 |
| 顺手清掉 | 未再使用的 `appendScopeToParams`（原来给旧 PHP 拼 scope 参数）、`GROUP_ONLY_PROCESS_CODES` 导入 |

### 12.4 待办

- [x] DTO/Dao/Mapper：`MaintenanceCaptureDTO` + `findCaptureLineMaintenanceRows`
- [x] Service：`findMaintenanceCaptureRows` + `parseCaptureListQuery`（`CaptureListQuery` record），跟 `findMaintenanceTransactionsRows` 同一套写法，`category` 复用 `normalizeMaintenanceCategory`
- [x] Controller：`POST /api/maintenance/capture-maintenance/list`（无 delete 端点）
- [x] 前端 `captureMaintenanceLogic.js` 切到新接口，Process 下拉换 Spring process-list，`updateSessionCompany` 补 `method: "POST"`
- [x] 删除功能全链路已实现（schema/Dao/Service/Controller/前端，见 §12.5），已按"一行一个 capture"的新粒度重做过一次
- [ ] 实机验证：能查到数据、Product/W-L Group 列在 GAME 无 description 时正确回退成 process code、Bank category（payroll-only 公司）和 Games category 都能各自查到数据不混列、**尤其要验证 §12.3 里 `category` 推断逻辑对不对**（有没有 Game 权限公司需要看 Bank 数据的场景）、多个不同 Product 属于同一 capture 时列表正确合并成一行、删除后列表正确标红
- [ ] §10.3.3 提到的其余几处（Payment/BankProcess Maintenance + UserListPage + useMemberWinLoss）仍未修，需要的话再统一处理

### 12.5 删除级联设计（schema + Service + Controller + 前端均已实现）

**删除单位是「整个 capture」**（同一 `data_captures.id` 下所有 `data_capture_line` 一起删），不是按单行勾选删——业务约定：不会出现同一 capture 下部分行删、部分行保留的情况。前端勾选到某一行时，后端要按该行的 `capture_id` 反查出该 capture 下的全部行一起处理，不能只删被勾选的那几行。

删除时要联动的三个地方，以及为什么各自需要加字段（详见对话记录，这里只记结论）：

| 联动目标 | 现状 | 加的字段 |
|---|---|---|
| `transactions`（旧版 Transaction 菜单页读的表） | 完全没有指回 `data_captures`/`data_capture_line` 的字段；一条 `data_capture_line`（金额非0）对应至多一条 `transactions`，不是一个 capture 对一条——已用真实数据核对（`capture_id=4` 的 4 条 line → 4 条 transaction，逐行一对一） | `data_capture_line.transaction_id`（nullable FK → `transactions.id`） |
| `process_submitted`（Data Capture 页面「已提交」标记，决定当天该 process 能不能重新提交） | 跟 `data_captures.id` 没有任何关联，只按 `tenant_id+process_id+capture_date` 存在性判断；因为删除单位已经是整个 capture，标记可以无条件删（不用再判断"底下还有没有活着的行"） | `process_submitted.capture_id`（nullable FK → `data_captures.id`） |
| Transaction Maintenance（§10，直接读 `data_capture_line`） | 已经是同一份数据源，只要归档表建好、列表查询 UNION 上去，删除会自动反映过去，不需要额外的删除动作 | 无需加字段，靠 `data_capture_line_deleted` 归档表 |

**Schema 改动（已完成，`backend/src/main/resources/sql/schema.sql` + 迁移脚本 `sql/migrate_capture_maintenance_delete.sql`，本地跑法见脚本头注释 `mysql -u root testcount < ...`，脚本内所有 ALTER 都做了 `INFORMATION_SCHEMA` 存在性检查，可重复执行）：**

1. `data_capture_line` 加 `transaction_id INT UNSIGNED NULL`（FK `transactions.id`，`ON DELETE SET NULL`）+ `idx_dcl_transaction` 索引。**历史数据限制**：这次改动前提交的行，`transaction_id` 永远 NULL，删这些老行时联动不了 transaction，只能软删 capture 侧本身。
2. **新建** `data_capture_line_deleted` 归档表：字段跟 `data_capture_line` 一一对应（含 `transaction_id`）+ `line_id`（原 id）+ `capture_id` + `deleted_by` + `deleted_at`，`data_captures` header 永不清理，即便旗下所有行都归档了。
3. `process_submitted` 加 `capture_id INT UNSIGNED NULL`（FK `data_captures.id`，`ON DELETE SET NULL`）+ `idx_sp_capture` 索引。

**Mapper/Dao/DTO 已完成**（Service 本轮明确不看）：

| 方法 | 作用 |
|------|------|
| `MaintenanceCaptureDTO.lineIds` | 删除请求字段：勾选的 `data_capture_line.id` 列表；语义上是"选中了哪些行"，不是"要删哪些 capture"——后续 Service 要先转成 distinct capture_id |
| `findCaptureLineMaintenanceDeletedRows` | Capture Maintenance 归档行查询，读 `data_capture_line_deleted`，跟活跃行同一套过滤/字段映射，`deleted=TRUE` |
| `findCaptureIdsByLineIdsAndTenantId` | 选中的 line id → distinct capture_id（第一步反查） |
| `findCaptureLineTransactionIdsByCaptureIdsAndTenantId` | 这些 capture 下所有活跃行的非空 `transaction_id`（要联动归档的） |
| `archiveCaptureTransactionsToDeleted` | 按显式 id 归档进 `transactions_deleted`（不是按 type/`bank_process_posted_id` 过滤——capture 生成的 WIN/LOSE 行两边都对不上，只能用显式 id 列表） |
| `archiveCaptureLineMaintenanceToDeleted` | 按 capture_id 归档进 `data_capture_line_deleted` |
| `deleteCaptureLineMaintenanceByCaptureIds` | 按 capture_id 硬删 `data_capture_line` |
| `deleteProcessSubmittedByCaptureIds` | 按 capture_id 删 `process_submitted`（无条件，因为删除单位就是整个 capture） |

硬删 transaction 那一步复用现成的 `deleteByIdsAndTenantId`，没有再写一个。

**⚠️ 依赖上一步的 schema 迁移**：这些方法引用了 `data_capture_line.transaction_id`、`data_capture_line_deleted` 表、`process_submitted.capture_id`——如果本地库还没跑 `sql/migrate_capture_maintenance_delete.sql`，调用会直接报字段/表不存在。

**Service 已完成**（Controller 用户自己写）：

- `MaintenanceService.deleteMaintenanceCaptureRows(MaintenanceCaptureDTO mc)` + `MaintenanceServiceImpl` 实现，`@Transactional`，顺序：
  1. `requireWritableSession`（非只读）+ `requireCaptureTenantId` + `requireCaptureLineIds`（`mc.lineIds` 不能为空）
  2. `lineIds` → `findCaptureIdsByLineIdsAndTenantId` 反查 distinct `captureIds`；查不到任何 capture 直接抛 `No matching capture maintenance records to delete`
  3. `findCaptureLineTransactionIdsByCaptureIdsAndTenantId` 拿这些 capture 下所有非空 `transaction_id`；非空才归档（`archiveCaptureTransactionsToDeleted`）+ 硬删（复用 `deleteByIdsAndTenantId`）——**没有关联 transaction 是正常情况**（历史行、或金额为 0 的行），不当错误处理，跳过即可
  4. 归档 line（`archiveCaptureLineMaintenanceToDeleted`）、硬删 line（`deleteCaptureLineMaintenanceByCaptureIds`），归档/删除数为 0 都当异常抛错（理论上不该发生，因为 captureIds 是刚从活跃行反查出来的）
  5. 删 `process_submitted`（`deleteProcessSubmittedByCaptureIds`）——**不检查返回条数**，因为历史 capture 的 `process_submitted.capture_id` 本来就可能是 NULL（补字段前的旧数据），查不到不算错误
- `MaintenanceServiceImpl.findMaintenanceCaptureRows` 改成合并 `findCaptureLineMaintenanceRows`（活跃）+ `findCaptureLineMaintenanceDeletedRows`（归档），按 `CC_ROW_ORDER` 排序——列表现在会真正显示已删除行了（红色/划线交给前端，后端只负责把两批数据合并返回）

**Controller 已完成**（用户自行实现，已核对）：`POST /api/maintenance/capture-maintenance/delete`，跟其余 Maintenance 端点同一套 `try/catch BusinessException` 写法。

**前端已完成**（`captureMaintenanceLogic.js`）：

- `deleteCaptureItems` 从旧 PHP `capture_maintenance/delete_api.php` 切到 `POST api/maintenance/capture-maintenance/delete`。请求体只有 `{tenantId, lineIds}`，`tenantId` 走 `resolveCaptureMaintenanceTenantId(scope)`（跟 `searchCaptureData` 同一个函数，同一套 `scope.scopeCompanyId ?? scope.uiCompanyId` 取法），`lineIds` 从调用方传入的 `items`（`[{capture_id, process_id, currency_id}]`，`process_id`/`currency_id` 是旧 PHP 时代留下的字段，新接口用不上）里提取 `capture_id`（= `data_capture_line.id`）去重后组装
- 函数签名从 `{items, dateFrom, dateTo, scope}` 简化成 `{items, scope}`——`dateFrom`/`dateTo` 新接口不需要（删除操作直接按 id 走，不用日期范围过滤），`CaptureMaintenancePage.jsx` 调用点还是照旧传 `dateFrom`/`dateTo`，多传的字段被忽略，不会报错，**没有改 `CaptureMaintenancePage.jsx`**
- 顺手清掉了不再使用的 `captureMaintenanceScopeApiParams` 导入（原来只有 `deleteCaptureItems` 拼 `company_id`/`view_group`/... 这些 scope 参数时用，现在整个文件已经找不到第二处用它的地方了）

### 12.6 Submit 流程回填 `transaction_id` / `capture_id`（已完成）

`DataCaptureSummaryServiceImpl.submit()`（[DataCaptureSummaryServiceImpl.java:462-479](../backend/src/main/java/com/eazycount/service/impl/DataCaptureSummaryServiceImpl.java)）调整了插入顺序：

- 原来：每行先 `toLineEntity(...)` 建好丢进 `lineEntities` 列表，金额非0再 `transactionDao.insert(...)`——line 实体建好时根本不知道自己的 transaction id 是多少。
- 现在：金额非0先 `transactionDao.insert(txn)`（`TransactionMapper.xml` 的 `insert` 本来就是 `useGeneratedKeys="true" keyProperty="id"`，insert 完 `txn.getId()` 立刻能拿到），把这个 id 传给 `toLineEntity(..., lineTransactionId)`，再统一批量插入 `data_capture_line`。金额为 0 的行 `lineTransactionId` 传 `null`（`data_capture_line.transaction_id` 本来就允许 NULL）。
- `insertProcessSubmitted` 调用加了 `captureId` 参数（`header.getId()` 在这行执行前早就有了，改起来比 transaction_id 简单，插入顺序都不用动）。

配套改动：`DataCaptureLine` 实体加 `transactionId` 字段；`DataCaptureSummaryMapper.xml` 的 `insertLines`（写入）和 `findLinesByCaptureId`（读取，供其它地方回填用）都加了 `transaction_id` 列；`DataCaptureDao.insertProcessSubmitted` 签名加 `captureId` 参数，`DataCaptureMapper.xml` 对应 INSERT 加 `capture_id` 列——全仓库只有 `DataCaptureSummaryServiceImpl.submit()` 一处调用，改签名不影响别处。

**至此，新提交的数据会自动带上 `transaction_id`/`capture_id`，Capture Maintenance 删除时的三处联动（transactions / process_submitted / 归档表）对新数据完全生效**。历史数据（这次改动前提交的）两个字段仍是 NULL，删历史 capture 时只能软删 capture 侧本身，联动不了 transaction/process_submitted——这是之前就确认过、无法回填的已知限制。

### 12.7 前端删除确认弹窗文案（已完成）

`MaintenanceDeleteConfirmModal` 本来就支持 `messageKey` prop 覆盖默认提示文案（`BankprocessMaintenancePage` 已经用 `deleteConfirmBankProcess` 这个先例）。加了 `deleteConfirmCaptureRecords`（`translateFile/pages/maintenanceTranslate.js` 的 `en`/`zh` 两个语言块都加了），`CaptureMaintenancePage.jsx` 的 `MaintenanceDeleteConfirmModal` 传 `messageKey="deleteConfirmCaptureRecords"`，文案明确提示"会连带删除同一次提交下的其它所有 Product"，不是默认那句通用的"删除已选中的 N 条记录"。

**还没做（下一轮）**：
- ~~`findTransactionLineMaintenanceRows` 也要拆活跃+归档，UNION 上 `data_capture_line_deleted`~~ ——**用户 2026-08-14 明确决定不需要**：Transaction Maintenance 只看有数据（活跃）的行即可，不必跟 Capture Maintenance 一样合并显示已删除行，见 §13.4
- 实机验证：
  1. 提交一条新 Data Capture（GAME 或 BANK 都测），去数据库确认对应的 `data_capture_line.transaction_id` 和 `process_submitted.capture_id` 都正确回填了（不是 NULL）
  2. 在 Capture Maintenance 里删掉这条新提交的数据，确认 `transactions`/`process_submitted` 两张表对应行都消失了（或者说 transactions 那边进了 `transactions_deleted`）
  3. 删一条改动前就存在的老数据，确认能正常软删（capture 侧），但 `transactions`/`process_submitted` 不会有变化（预期内，不是 bug）
  4. 前端删除确认弹窗文案在 Capture Maintenance 页面显示正确（跟 Payment/BankProcess Maintenance 默认文案不一样）
  5. 前提：本地库要先跑过 `migrate_capture_maintenance_delete.sql`

---

## 13. 已知待修问题（2026-08-13 复查记录）

对五个 Maintenance 页面（Payment / Bank Process / Capture / Formula / Transaction）做了一轮 company/games-bank 相关的复查（group 部分未看）。核心 List/Edit/Delete 端点确认全部已在 Spring，没有整支功能漏做的页面；但发现下面几个仍未修的具体缺口，先记录，不在这轮动手改。

### 13.1 Payment / Bank Process Maintenance 切公司仍是裸 GET（405/500）

`updateSessionCompany` 在 Capture / Formula / Transaction 三页已经补上 `method: "POST"`（见 §10.3.3），但 **Payment 和 Bank Process 这两页还没修**：

- `Count-frontend/src/pages/maintenance/payment/paymentMaintenanceLogic.js:230` —— 裸 `fetch(...)`，无 `method`，默认 GET
- `Count-frontend/src/pages/maintenance/bankprocess/bankprocessMaintenanceLogic.js:310` —— 同上

`AuthController.switchTenant` 只收 POST，这两页切公司会 405/500。修法就是照抄 Capture/Formula 已经改好的写法加一行 `method: "POST"`。

### 13.2 `api/domain/domain_api.php` 从未在 Spring 实现，Capture/Formula/Transaction 仍在调用

`maintenanceCompanyApi.js` 的 `fetchDomainCompanyPermissions` 打的是 `api/domain/domain_api.php`，Spring `DomainController` 没有对应路由（只有 `/list`/`/add`/`/update`/`/delete`/`/list-fee`/`/add-fee`，没有 `get_company_permissions` 这个 action），必然 500，catch 后静默 fallback 成写死的默认权限列表（`["Games","Bank",...]`，Games 排最前）。

- Formula/Transaction 已经靠繞開這条路径解决了实际症状（改读 scope 上的 `c168Channel`/`companyPayrollChannel`，不再依赖这个权限列表本身），但底层这个坏接口没有被替换，只是被绕开——如果以后有新逻辑直接信了这个权限列表的值，会重新踩坑。
- 顺手确认：`paymentMaintenanceLogic.js` 里的 `fetchCompanyPermissions`（同样打这个坏接口）在 Payment Maintenance 页面里全仓库 grep 确认零调用，是死代码，目前无实际影响。
- 同文件里的 `fetchMaintenanceProcesses`（打另一个旧 PHP `api/processes/processlist_api.php`）也是全仓库零调用的死代码。
- 三个都在 `Count-frontend/src/pages/maintenance/shared/maintenanceCompanyApi.js`。

### 13.3 Capture Maintenance 的 category 二选一逻辑覆盖不了「Game+Bank 都有权限」的公司

`Count-frontend/src/pages/maintenance/capture/captureMaintenanceLogic.js` 的 `resolveCaptureMaintenanceCategory`：

```js
function resolveCaptureMaintenanceCategory(scope) {
  const payrollChannel = Boolean(scope?.c168Channel || scope?.companyPayrollChannel);
  return payrollChannel ? "Bank" : "Games";
}
```

`category` 在 SQL 里是必填硬条件（§12.1，防 Select All 时 GAME/BANK 混列），所以选错等于那部分数据对这页面完全不可见。如果一间公司同时有 Game 和 Bank 权限、但没被判定成 C168 或 bank-only（没打上 `companyPayrollChannel`），这页会永远只查 Games 分类，Bank 分类的 capture 记录完全查不到，UI 上也没有手动切换的地方。

已核对过 `captureMaintenanceScope.js` 的 enrich 逻辑本身没问题（跟 Transaction Maintenance 抄的是同一份写法），不是 §11.3.3 那种"忘记补 flag"的重演，是这个二分类设计本身覆盖不了"两种权限都有"的公司——跟文件 §12.3/§12.4 当初标注的「⚠️ 推断，需要实机验证」是同一个未解决项，复查后确认到现在仍未修。

### 13.4 Transaction Maintenance 看不到已删除行 —— **非缺口，用户已确认不需要**

`findTransactionLineMaintenanceRows` 没有接 `data_capture_line_deleted` 归档表的 UNION 查询，跟 Capture Maintenance 的 live+archived 合并不同步（§10.4、§12.7 曾记过这项 TODO）。**2026-08-14 用户明确决定**：Transaction Maintenance 只需要看有数据（活跃）的行，不需要展示已删除行，这项不用做，§10.4/§12.7 里的对应 TODO 已作废。

### 13.5 待办清单

- [ ] `paymentMaintenanceLogic.js` / `bankprocessMaintenanceLogic.js` 的 `updateSessionCompany` 补 `method: "POST"`（见 §13.1）
- [ ] 实机验证一间「Game+Bank 都有权限、非 C168/非 bank-only」的公司，确认 §13.3 的 Capture Maintenance Bank 数据不可见问题是否真的复现
- [ ] 视 §13.3 验证结果决定怎么修 `resolveCaptureMaintenanceCategory`（可能需要 UI 加分类切换，而不是纯二选一自动判断）
- [ ] `api/domain/domain_api.php` 要嘛在 Spring 补一个真正的权限查询端点，要嘛把 `fetchDomainCompanyPermissions`/`fetchCompanyPermissions`（Payment 死代码）/`fetchMaintenanceProcesses`（死代码）一并从 `maintenanceCompanyApi.js` 清掉（见 §13.2）
- [x] ~~`findTransactionLineMaintenanceRows` 补 `data_capture_line_deleted` 归档表 UNION~~ —— 用户已确认不需要，Transaction Maintenance 只看活跃行即可（见 §13.4）
