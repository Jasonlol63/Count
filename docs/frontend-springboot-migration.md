# 前端 Spring Boot 迁移记录

> **用途**：记录 `Count-frontend/` 在 PHP → Spring Boot 迁移过程中**已改动的部分**，供后续开发 / AI 会话快速对齐上下文。  
> **后端契约说明**见同目录 [`login-to-business-pages.md`](./login-to-business-pages.md)。  
> **前端仓库路径**：`../Count-frontend/`（与本 `Count/` 后端仓库并列）。

**最后更新**：2026-07-14（Process delete：单条 Spring + 前端循环，同 Account）

---

## 目录

1. [总体策略](#1-总体策略)
2. [迁移状态一览](#2-迁移状态一览)
3. [公共层改动](#3-公共层改动)
4. [各模块已改文件](#4-各模块已改文件)
5. [Ownership 专项（2026-07-13）](#5-ownership-专项2026-07-13)
6. [Spring ↔ 前端字段对照](#6-spring--前端字段对照)
7. [尚未迁移 / 仍走 PHP](#7-尚未迁移--仍走-php)
8. [维护约定](#8-维护约定)
9. [Process List 专项（2026-07-14）](#9-process-list-专项2026-07-14)

---

## 1. 总体策略

### 1.1 两种接入方式

| 方式 | 说明 | 示例 |
|------|------|------|
| **A. `apiUrl.js` 重写** | 页面仍写旧 PHP URL，`buildApiUrl()` 在运行时映射到 Spring | `get_owners_api.php` → `api/ownership/list` |
| **B. 直接调 Spring** | 新代码直接写 Spring 路径 + 独立 `*Api.js` normalize | `api/domain/list`、`api/userlist/list` |

**原则（2026-07-13 起）**：新迁移以 **Spring Boot 响应格式为准**；前端在边界做 `normalize*`，**不要求后端迁就 PHP 字段**。

### 1.2 租户 ID 约定

- UI 里公司 pill 的 `company.id` / ownership 卡片的 `comp.id` = 后端 **`tenant.id`（数字）**。
- API 查询参数统一为 **`tenant_id`**（支持数字 id 或 tenant code，如 `C168`、`AP`）。
- 父级 Group 在 UI 仍用 **`group_id` = parent tenant code**（如 `AP`），来自 `parent_tenant_code`。

### 1.3 成功响应判断

```javascript
res.success === true || res.status === "success"
```

封装：`ownershipHelpers.isApiSuccess()` 等。

---

## 2. 迁移状态一览

| 模块 | 状态 | Spring 前缀 | 前端适配方式 |
|------|------|-------------|--------------|
| **Auth / Session** | ✅ 已迁移 | `/auth/*` | 直接调 Spring + `sessionTenant.js` |
| **Tenant 列表** | ✅ 已迁移 | `GET /auth/tenant-accessible` | `tenantAccessibleApi.js` |
| **Domain** | ✅ 已迁移 | `/api/domain/*` | `domainApi.js` + `domainHelpers.js` |
| **Admin (User List)** | ✅ 已迁移 | `/api/userlist/*` | `userListApi.js` |
| **Account (Member)** | ✅ 已迁移 | `/api/account/*` | `accountListApi.js` |
| **Currency** | ✅ 已迁移 | `/api/currency/*` | `currencyApi.js` |
| **Announcement / Maintenance** | ✅ 已迁移 | `/api/announcement/*` | `apiUrl.js` 重写（页面仍写 PHP 路径） |
| **Auto Renew** | ⚠️ 部分 | `/api/auto-renew/*` | 列表经 `apiUrl` 重写；reject 等已直调 Spring |
| **Ownership** | ✅ API 已迁移 + **数据层已对齐 Spring** | `/api/ownership/*` | `apiUrl.js` 重写 + `ownershipRowHelpers` normalize |
| **Process** | ⚠️ 部分 | `/api/process/*` | **列表 + description CRUD + add/update/status/delete + Edit 回填** 已 Spring；form meta（`addprocess_api`）等仍混用 PHP |
| **Transaction / Report / Data Capture / Bank Process / Member** | ❌ 大多仍 PHP | — | 未系统迁移 |

---

## 3. 公共层改动

### 3.1 `Count-frontend/src/utils/core/apiUrl.js`

**核心**：`buildApiUrl(pathAndQuery)` 将旧 PHP 路径透明映射到 Spring。

已配置重写的模块：

| 旧 PHP 路径前缀 | Spring 目标 |
|----------------|-------------|
| `api/session/*` | `auth/*` |
| `api/transactions/get_owner_companies_api.php` | `auth/tenant-accessible` |
| `api/ownership/get_companies_api.php` | `auth/tenant-accessible` |
| `api/ownership/get_group_earnings_api.php` | `auth/tenant-accessible` |
| `api/ownership/get_owners_api.php` | `api/ownership/list?tenant_id=` |
| `api/ownership/get_group_owners_api.php` | `api/ownership/list?tenant_id=` |
| `api/ownership/get_available_accounts_api.php` | `api/ownership/available-accounts?tenant_id=` |
| `api/ownership/get_group_available_accounts_api.php` | `api/ownership/available-accounts?tenant_id=` |
| `api/subscription/auto_renew_api.php` | `api/auto-renew/list` |
| `api/announcements/*` | `api/announcement/*` |
| `api/maintenance/*` | `api/announcement/*`（维护公告） |

参数转换示例：`company_id` / `group_id` → `tenant_id`。

### 3.2 `utils/company/tenantAccessibleApi.js`

- `GET auth/tenant-accessible`（经 `apiUrl` 或直调）
- `normalizeTenantAccessibleItem()`：Spring 行 → `{ tenantId, tenantCode, parentTenantCode, tenantType, ... }`
- `tenantAccessibleRowToUiTenant()`：供侧边栏 / 公司切换 pill 使用

### 3.3 `utils/auth/sessionTenant.js`

从 `auth/current-user` 的 `SessionUser` 读取：

- `tenant_id` / `tenant_code`
- `tenant_has_game` / `tenant_has_bank`
- `is_current_tenant_c168`
- `permissions[]`（小写，见 `sidebarPermissions.js`）

### 3.4 `utils/company/loginScope.js` / `sharedCompanyFilter.js`

- Group / Company 登录形态、`group_id` 筛选、C168 Domain/AutoRenew 入口
- 与 Spring `tenant_type`、`parent_tenant_code` 对齐（非旧 `company` 表字段）

---

## 4. 各模块已改文件

### 4.1 Auth / Login

| 文件 | 改动 |
|------|------|
| `pages/login/LoginPage.jsx` | `auth/login`、`auth/current-user` |
| `pages/login/SecondaryPasswordPage.jsx` | `auth/verify-*-secondary-password` |
| `pages/login/resetPassword.js` | `auth/send-reset-tac`、`auth/reset-password` |

### 4.2 Domain

| 文件 | 改动 |
|------|------|
| `pages/domain/domainApi.js` | 直调 `api/domain/list|add|update|update-setting` |
| `pages/domain/domainHelpers.js` | `featureModules` ↔ UI permissions；`groupToTenantSaveEntry` / `companyToTenantSaveEntry` |
| `pages/domain/DomainPage.jsx` 等 | 消费 aggregate 后的 owner+tenant 结构 |

### 4.3 Admin (User List)

| 文件 | 改动 |
|------|------|
| `pages/userlist/userListApi.js` | `api/userlist/list|get|add|update|delete`；`normalizeAdminListItem` |
| `pages/userlist/UserListPage.jsx` | `resolveListTenantId()`：`company.id` → `tenant_id` |

### 4.4 Account (Member 账号)

| 文件 | 改动 |
|------|------|
| `pages/account/accountListApi.js` | `api/account/list|add|update|delete`；`normalizeAccountListItem` |
| `pages/account/AccountListPage.jsx` | 客户端 search/status 过滤（Spring list 暂无对应 query） |

### 4.5 Currency

| 文件 | 改动 |
|------|------|
| `utils/api/currencyApi.js` | `api/currency/list|available|account/linked-accounts` 等 |

### 4.6 Announcement

| 文件 | 改动 |
|------|------|
| `pages/announcement/AnnouncementPage.jsx` | 仍写 PHP 文件名，经 `apiUrl.js` 转到 `api/announcement/*` |

### 4.7 Auto Renew

| 文件 | 改动 |
|------|------|
| `pages/autorenew/autoRenewLogic.js` | 列表：`api/subscription/auto_renew_api.php` → `api/auto-renew/list`；`reject` 直调 `api/auto-renew/reject` |

### 4.8 Ownership（详见第 5 节）

| 文件 | 改动 |
|------|------|
| `pages/ownership/ownershipRoutePrefetch.js` | `tenant-accessible` → 映射为 UI `company` 行 |
| `pages/ownership/company/useCompanyOwnership.js` | 去掉 PHP `G_AP` 注入；normalize 候选列表 |
| `pages/ownership/group/useGroupEarnings.js` | 同上 |
| `pages/ownership/shared/ownershipRowHelpers.js` | **Spring DTO 适配层** |
| `pages/ownership/shared/components/OwnAccountSelect.jsx` | 使用 `account_id` |
| `pages/ownership/shared/components/AccountEditorRow.jsx` | Group 行锁定、下拉去重 |
| `pages/ownership/company/components/CompanyCard.jsx` | 传入 `allRows` |
| `pages/ownership/group/components/GroupEarningCard.jsx` | 传入 `allRows` |

**已直调 Spring（不经 PHP 文件名）的 ownership 写操作**：

- `POST api/ownership/batch-save-ownership`
- `POST api/ownership/link-partner`
- `POST api/ownership/update-parent-tenant`

### 4.9 Process List（详见第 9 节）

| 文件 | 改动 |
|------|------|
| `pages/processlist/processListApi.js` | `process-list` / description CRUD / **`addProcess`** / **`updateProcess`** / **`updateProcessStatus`** |
| `pages/processlist/processListHelpers.js` | `normalizeProcessListItem`；`dayUseIdsFromListRow` / `buildEditDescriptionSelection`（Edit 用 list 行） |
| `pages/processlist/processRoutePrefetch.js` | 列表改走 `fetchProcessListByTenantId` |
| `pages/processlist/ProcessListPage.jsx` | description CRUD + add/update/status；**`openEdit` 用 `rows` 回填** |

**后端契约同步（本仓）**：

- `ProcessController`：`POST /add-process`、`POST /update-process`、`POST /update-status`、`POST /delete-process`；list/description 均为 RequestBody
- `ProcessDTO` 扁平写：`id?`（update）、`tenantId, code`（add）、`currencyId, descriptionIds, dayOfWeeks, removeWord, ...`
- Update 子表：按 `processId` **先删再插** `process_description_link` / `process_day`
- Status：`update-status` body `{ id, tenantId }`；返回 `Process`，前端读 `data.status`
- Delete：`delete-process` body `{ id, tenantId }`（单条，同 Account；须 INACTIVE；子表靠 CASCADE）；前端多选循环调用
- `process.created_by` / `updated_by`：`String` / `VARCHAR(50)` 存 `session.login_id`（admin=`user.login_id`，owner=`owner_code`）
- DB：`process` + `process_description_link` + `process_day`

---

## 5. Ownership 专项（2026-07-13）

### 5.1 背景问题

迁移初期前端仍按 **PHP 约定**处理数据，与 Spring `TenantOwnershipDTO` 不一致，导致：

1. **重复 AP 选项**：PHP 用 `G_AP`（group code），Spring 用 `G_{tenantId}`（如 `G_5`）；前端手工注入 `G_AP` 与已保存的 `G_5` 并存。
2. **显示名不一致**：Spring `/list` 对 group 返回 `account_name: "AP"`，候选 `/available-accounts` 返回 `account_name: "Group: AP"`。
3. **字段名不一致**：PHP 候选用 `id` + `type`；Spring 用 `account_id` + `owner_type`。

### 5.2 解决原则

**以 Spring Boot 为准**，前端统一 normalize，不改后端去模仿 PHP。

### 5.3 新增适配函数（`ownershipRowHelpers.js`）

| 函数 | 作用 |
|------|------|
| `formatSpringOwnershipLabel(dto)` | Group：`account_name` 无 `Group:` 前缀时自动补全 |
| `normalizeOwnershipAccount(dto)` | Spring/遗留 DTO → 统一 picker 结构 |
| `normalizeOwnershipAccounts(data)` | 批量 normalize |
| `mapOwnerApiRows(data)` | `/list` 行 → 编辑器 row（含 `account_label`） |
| `mergeEditorAccounts(picker, rows)` | 候选 + 已保存行合并（key = `account_id`） |
| `accountsForRowPicker(accounts, currentId, allRows)` | 排除他行已选账户 |

### 5.4 删除的 PHP 逻辑

**`useCompanyOwnership.js`** 中已删除：

```javascript
// 已删除：按 group_id 手工 push { id: `G_${compGid}`, account_name: `Group: ${compGid}` }
```

Group 候选完全依赖 Spring `GET /api/ownership/available-accounts`。

### 5.5 Group 股东 Spring 契约（前端必须遵守）

| 字段 | Group 类型约定 |
|------|----------------|
| `account_id` | `G_{partnerTenantId}`，如 `G_5`（**不是** `G_AP`） |
| `owner_type` | `"group"` |
| `role` | `"GROUP"` |
| `account_name` | `/list` → tenant code（`AP`）；`/available-accounts` → `Group: AP` |
| `name` | `Group Equity` |
| `partner_tenant_id` | 数字 tenant id |

保存 payload（`rowsToSavePayload`）只发：

```json
{ "account_id": "G_5", "percentage": 20, "read_only": 1, "sort_order": 0 }
```

### 5.6 UI 行为变更

1. **显示**：已保存 Group 行统一显示为 **「Group: AP」**（由 `formatSpringOwnershipLabel` 处理）。
2. **下拉去重**：同一 `account_id` 不会在其他行重复出现。
3. **锁定**：已保存的 Group 行（有 `ownership_id`）账户下拉 **disabled**，不可再改选。

### 5.7 公司列表预取（`ownershipRoutePrefetch.js`）

仍调用经重写的 `get_companies_api.php` → `auth/tenant-accessible`，并映射为 UI 结构：

```javascript
{
  id: t.tenant_id,           // 数字，用于 API tenant_id
  name: t.tenant_code,
  company_id: t.tenant_code,
  group_id: t.parent_tenant_code,
  expiration_date: t.expiration_date,
}
```

### 5.8 后端同期修复（供对照）

`backend/.../TenantOwnership.xml`：`user.role` → `JOIN user_role ur` + `ur.code`（`user` 表已改为 `role_id`）。  
属后端修复，非前端迁移，但 ownership 联调时需前后端同时生效。

### 5.9 已知缺口

| 项 | 说明 |
|----|------|
| 历史月 `meta.has_snapshot` / `meta.saved_at` | Spring `/list` 目前仅 `is_historical`、`effective_month`；历史 banner 可能不完整 |
| URL 仍写 PHP 文件名 | 读接口经 `apiUrl` 重写；可逐步改为直写 `api/ownership/*` |

---

## 6. Spring ↔ 前端字段对照

### 6.1 通用租户（`tenant-accessible`）

| Spring JSON | 前端 UI |
|-------------|---------|
| `tenant_id` | `company.id` / API `tenant_id` |
| `tenant_code` | `company_id` / `name` / 显示 code |
| `tenant_type` | `GROUP` / `COMPANY` |
| `parent_tenant_code` | `group_id` |
| `expiration_date` | `expiration_date` |

### 6.2 Ownership（`TenantOwnershipDTO`）

| Spring JSON | 前端内部 |
|-------------|----------|
| `account_id` | `row.account_id` / picker `account_id`（**勿用** `id`） |
| `account_name` | `account_name`；Group 显示用 `account_label` |
| `name` | `display_name` / `name` |
| `owner_type` | `owner_type`（**勿用** PHP `type`） |
| `role` | `role` |
| `ownership_id` | `ownership_id`（有值 = 已持久化） |
| `partner_tenant_id` | `partner_tenant_id` |
| `read_only` | `read_only`（0/1） |
| `is_external_partner` | `is_external_partner`（0/1 → boolean） |

### 6.3 Admin / Account list

| Spring | 前端 |
|--------|------|
| `admin.loginId` | `loginId` |
| `accountId` | `account_id` |
| `scopeTenantId` | `scope_tenant_id` |

### 6.4 Process list（`ProcessDTO`）

| Spring JSON | 前端表格行（normalize 后） |
|-------------|---------------------------|
| `process.code` | `process_name`（列 Process ID） |
| `processDescriptions[].name` | 拼成 `description`（列 Description） |
| `process.status` | `status`（小写 active/inactive） |
| `currencyCode`（DTO 顶层） | `currency` |
| `processDays[].dayOfWeek`（1–7） | 拼成 `day_use`（如 `MON,THU`） |
| `process.id` | `id` |
| `process.currencyId` | `currency_id` |
| `process.createdBy` / `updatedBy`（String = `login_id`） | `created_by` / `updated_by`（Edit 直接展示） |

展示字符串 **只在前端** `formatProcessDescriptionLabel` / `formatProcessDayUseLabel` 生成；API 保持结构化 list。

---

## 7. 尚未迁移 / 仍走 PHP

以下模块**未**在 `apiUrl.js` 中做 Spring 重写，或仅部分 endpoint 迁移：

- **Process 写操作 / 详情**：部分 form meta 仍 PHP；**列表 + description CRUD + add/update/status/delete + Edit 自 list 回填** 已走 Spring / 前端
- **Transaction / Payment**：`api/transactions/*`
- **Report**：`api/reports/*`
- **Data Capture / Summary**：`api/datacapture/*`、`api/summary/*`
- **Bank Process List**：大量 `api/bankprocesses/*`、`api/accounts/*`
- **Member Win/Loss**：`api/member/*`、`api/accounts/account_company_api.php`
- **Maintenance 业务页**（formula/transaction/payment 等）：仍 PHP
- **User Access 部分接口**

新增迁移时：优先在对应 `*Api.js` 增加 `normalize*`，并更新本节状态表。

---

## 8. 维护约定

1. **每次完成前端 Spring 适配**，更新本文「迁移状态一览」+ 对应模块小节 + **最后更新日期**。
2. **凡改动前端与 Spring Boot 的桥接**（URL、入参、`normalize*`、DTO 字段对照），**必须**写入本文（状态表 + 字段对照 + 模块小节）；不要只改代码不记文档。
3. **Ownership 类问题**：先查第 5 节是否又用回 `id`/`G_{code}` 等 PHP 假设。
4. **Process list 展示**：Description / Day Use 字符串在前端拼，勿在 list API 再塞 `GROUP_CONCAT`（编辑仍要数组）。
5. **新接口**：优先直写 Spring URL；仅在需兼容大量旧调用时才扩展 `apiUrl.js`。
6. **相关文档**：
   - 后端 API 行为：`login-to-business-pages.md`
   - 前端 ownership 代码索引：`Count-frontend/src/pages/ownership/README.md`

---

## 9. Process List 专项（2026-07-14）

### 9.1 背景

`process` 表去掉 JSON 后，Spring list 返回：

- `process`（含 `code` / `status` / `currencyId` …）
- `processDescriptions[]`
- `processDays[]`（`dayOfWeek` 1=Mon…7=Sun）
- `currencyCode`（join `currency.code`，挂在 DTO 顶层）

表格列需要的是扁平展示串；**转换放在前端**，API 保持结构化。

### 9.2 桥接约定

| 项 | 约定 |
|----|------|
| URL | `POST /api/process/process-list`（**无** query；tenantId 不出现在 URL） |
| Body | JSON 数字，如 `12`（`@RequestBody Integer tenantId`） |
| `tenantId` | UI `company.id` = `tenant.id` |
| 成功 | `success === true`（或 `status === "success"`） |
| normalize | `normalizeProcessListItem` / `normalizeRows` |
| Description | `formatProcessDescriptionLabel(processDescriptions)` → `description` |
| Day Use | `formatProcessDayUseLabel(processDays)` → `day_use`（如 `MON,THU`） |
| Currency | `currencyCode` → `currency` |
| Process ID | `process.code` → `process_name` |

### 9.3 Description CRUD（前后端对齐）

| 接口 | Body | 前端入口 |
|------|------|----------|
| `POST /api/process/list-description` | JSON 数字 `tenantId` | `fetchProcessDescriptionsByTenantId` |
| `POST /api/process/add-description` | `{ tenantId, name }` | `addProcessDescription` |
| `POST /api/process/delete-description` | `{ id, tenantId }` | `deleteProcessDescription` |

URL **不**带 `tenant_id` / `id`。`ProcessListPage` 的 `loadFormMeta` / `reloadDescriptions` / add / delete 已改走上述 API。

### 9.5 Process Add（后端，2026-07-14）

| 项 | 约定 |
|----|------|
| URL | `POST /api/process/add-process`（RequestBody，无 query） |
| Body | `{ tenantId, code, currencyId, descriptionIds[], dayOfWeeks[], removeWord, replaceWordFrom, replaceWordTo, remark }` |
| `dayOfWeeks` | `1=Mon … 7=Sun`，非法值忽略；空数组则不插 `process_day` |
| `descriptionIds` | 须属于同 `tenantId`；写 `process_description_link` |
| 查重 | `findProcessCodeByTenantId`（code trim+upper） |
| 成功 `data` | 回写后的 `ProcessDTO`（含 `id`） |
| 前端 | `addProcess()` → `ProcessListPage` 新建提交 |

### 9.6 Process Update（前后端，2026-07-14）

| 项 | 约定 |
|----|------|
| URL | `POST /api/process/update-process`（RequestBody，无 query） |
| Body | `{ id, tenantId, currencyId, descriptionIds[], dayOfWeeks[], removeWord, replaceWordFrom, replaceWordTo, remark }` |
| `code` | **不更新**（编辑只读） |
| 校验 | body 非空；`id`+`tenantId` 对齐已有行；`currencyId` 属同 tenant |
| 主表 | `updateProcessDetails`（`WHERE id AND tenant_id`） |
| 子表 | `delete*ByProcessId` → 非空再 `insert*Batch`（空列表 = 清空） |
| 前端 | `updateProcess()` → `ProcessListPage` `editMode` 提交（不再走 PHP `update_process`） |

### 9.6.1 Process Edit 打开（前端 list 回填，2026-07-14）

| 项 | 约定 |
|----|------|
| 不调用 | PHP `processlist_api.php?action=get_process`；也未做 Spring `get-process` |
| 数据源 | 当前页已加载的 `rows`（`normalizeProcessListItem` 后）按 `id` 查找 |
| Description | `process_descriptions` / `description_ids` → `buildEditDescriptionSelection` |
| Day Use | `process_days[].dayOfWeek`（1–7）→ `dayUseIdsFromListRow`，**不用**展示串 `MON,THU` |
| 字段 | `remove_word` / `replace_*` / `remark` / `currency_id` / `created_at` / `updated_at` 等直接取自行 |
| 操作人 | `created_by` / `updated_by` = 库里存的 `login_id`（owner=`owner_code`，admin=`user.login_id`），非 user.id |

### 9.6.2 Process Update Status（前后端，2026-07-14）

| 项 | 约定 |
|----|------|
| URL | `POST /api/process/update-status`（RequestBody，无 query） |
| Body | `{ id, tenantId }`（对齐 `Process` 字段；**无** PHP query） |
| 行为 | 服务端 `ACTIVE` ↔ `INACTIVE`；校验 `id` 属于 `tenantId` |
| 成功 `data` | 更新后的 `Process`（读 `data.status`：`ACTIVE`/`INACTIVE`） |
| 前端 | `updateProcessStatus(tenantId, id)` → 行上 `status` 小写化；**不**用 `newStatus` |

### 9.6.3 Process Delete（前后端，同 Account 单条循环，2026-07-14）

| 项 | 约定 |
|----|------|
| URL | `POST /api/process/delete-process`（RequestBody，无 query） |
| Body | `{ id, tenantId }`（**无** `ids[]`；批量由前端循环） |
| 校验 | 存在且属 tenant；status **必须** `INACTIVE` |
| 子表 | 只删 `process`；`process_description_link` / `process_day` / `process_submitted` 靠 **ON DELETE CASCADE** |
| 前端 | `deleteProcess(tenantId, id)`；多选 `for … of selectedIds` |

### 9.7 已知缺口

| 项 | 说明 |
|----|------|
| Add form meta | 部分仍 `addprocess_api.php`（`existingProcesses` / days 等） |
| List process 服务端 search / showInactive | 暂无；✅ 客户端 `applyProcessFilters`（`fetchGamesProcessListSlice`） |
| 前端 add/update/status/delete | ✅ `addProcess` / `updateProcess` / `updateProcessStatus` / `deleteProcess` |
| Edit 打开 | ✅ list 行本地回填（无 get API） |

---

## 附录：快速文件索引

```
Count-frontend/src/utils/core/apiUrl.js          # PHP → Spring URL 重写表
Count-frontend/src/utils/company/tenantAccessibleApi.js
Count-frontend/src/utils/auth/sessionTenant.js
Count-frontend/src/pages/domain/domainApi.js
Count-frontend/src/pages/userlist/userListApi.js
Count-frontend/src/pages/account/accountListApi.js
Count-frontend/src/utils/api/currencyApi.js
Count-frontend/src/pages/ownership/shared/ownershipRowHelpers.js   # Ownership Spring 适配
Count-frontend/src/pages/ownership/company/useCompanyOwnership.js
Count-frontend/src/pages/ownership/group/useGroupEarnings.js
Count-frontend/src/pages/ownership/ownershipRoutePrefetch.js
Count-frontend/src/pages/processlist/processListApi.js             # process-list + description CRUD + add/update (RequestBody)
Count-frontend/src/pages/processlist/processListHelpers.js         # desc / dayUse 展示转换
Count-frontend/src/pages/processlist/processRoutePrefetch.js
Count-frontend/src/pages/processlist/ProcessListPage.jsx
```
