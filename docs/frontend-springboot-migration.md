# 前端 Spring Boot 迁移记录

> **用途**：记录 `Count-frontend/` 在 PHP → Spring Boot 迁移过程中**已改动的部分**，供后续开发 / AI 会话快速对齐上下文。  
> **后端契约说明**见同目录 [`login-to-business-pages.md`](./login-to-business-pages.md)。  
> **前端仓库路径**：`../Count-frontend/`（与本 `Count/` 后端仓库并列）。

**最后更新**：2026-07-20（Domain Share % Add Account tenant 对齐 + Domain 页全量 Spring + Account 页面全量 Spring + Share % owner_type/Profit percentage 业务对齐 + Domain Confirm Charge on Save 记账落地）

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
10. [Bank Process List 专项（2026-07-15）](#10-bank-process-list-专项2026-07-15)
11. [Transaction BP-only 列表（2026-07-20）](#11-transaction-bp-only-列表2026-07-20)
12. [彻底去除 UPLINE 账户角色（2026-07-20）](#12-彻底去除-upline-账户角色2026-07-20)
13. [Account List 全量 Spring（2026-07-20）](#13-account-list-全量-spring2026-07-20)
14. [Domain Share % Add Account tenant 对齐（2026-07-20）](#14-domain-share--add-account-tenant-对齐2026-07-20)

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
| **Auth / Session** | ✅ 已迁移 | `/auth/*` | **直调** `authApi.js`（无 PHP session 路径 / 无 rewrite） |
| **Tenant 列表** | ✅ 已迁移 | `GET /auth/tenant-accessible` | `tenantAccessibleApi.js` |
| **Domain** | ✅ 已迁移 | `/api/domain/*` | `domainApi.js` + `domainHelpers.js` |
| **Admin (User List)** | ✅ 已迁移 | `/api/userlist/*` | `userListApi.js` |
| **Account (Member)** | ✅ 全量已迁移 | `/api/account/*` + `/api/currency/*` | `accountListApi.js` + `currencyApi.js`；`AccountListPage` 直调 Spring |
| **Currency** | ✅ 已迁移 | `/api/currency/*` | `currencyApi.js` |
| **Announcement / Maintenance** | ✅ 已迁移 | `/api/announcement/*` | `apiUrl.js` 重写（页面仍写 PHP 路径） |
| **Auto Renew** | ⚠️ 部分 | `/api/auto-renew/*` + Domain Comm | 列表 / reject / **approve** 直调 Spring；Comm 用 `domain/list` + `update-setting` |
| **Ownership** | ✅ API 已迁移 + **数据层已对齐 Spring** | `/api/ownership/*` | `apiUrl.js` 重写 + `ownershipRowHelpers` normalize |
| **Process** | ⚠️ 部分 | `/api/process/*` | **列表 + description CRUD + add/update/status/delete + Edit 回填** 已 Spring；form meta（`addprocess_api`）等仍混用 PHP |
| **Bank Process** | ⚠️ 部分 | `/api/bank-process/*`、`/api/bank-country-option/*`、`/api/account/*` | **列表（含 shares）+ catalog + Add/Update/Status/Delete/Remark + Edit list 回填** 已 Spring；Due 仍 PHP |
| **Transaction / Report / Data Capture / Member** | ⚠️ 部分 | `/api/transaction/search` + `/history` + `/submit` + Meta | **Submit**：`PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`/`ADJUSTMENT` 已 Spring；Contra Inbox / RATE 仍 PHP |

---

## 3. 公共层改动

### 3.1 `Count-frontend/src/utils/core/apiUrl.js`

**核心**：非 Auth 模块仍可用 `buildApiUrl()` 把旧 PHP 路径映射到 Spring。  
**Auth 例外**：login / current-user / logout / switch-tenant / secondary / reset **禁止**走 PHP 路径或 rewrite，统一 `utils/auth/authApi.js` 直写 `/auth/*`。

已配置重写的模块（**不含** `api/session/*`）：

| 旧 PHP 路径前缀 | Spring 目标 |
|----------------|-------------|
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
| `pages/login/LoginPage.jsx` | `authApi.loginWithTenant` / `fetchCurrentUser` |
| `pages/login/SecondaryPasswordPage.jsx` | `verifyOwner/UserSecondaryPassword` + `logoutSession` |
| `pages/login/resetPassword.js` | `sendResetTacRequest` / `resetPasswordRequest` |
| `utils/auth/authApi.js` | 统一 Spring `/auth/*`（login / current-user / logout / switch-tenant / secondary / reset） |
| `components/AuthenticatedLayout.jsx` | `fetchCurrentUser` + `logoutSession` + `permissions` / `tenant_has_*` |
| `utils/company/companySessionSync.js` | `switchSessionTenant`（不再走 PHP update_company_session） |

### 4.2 Domain

| 文件 | 改动 |
|------|------|
| `pages/domain/domainApi.js` | 直调 Spring：`list` / `add` / `update` / `update-setting` / `delete` / `list-fee` / `add-fee`；`aggregateOwnerTenantRows`；Share 账户复用 `account/list` |
| `pages/domain/domainHelpers.js` | `featureModules` ↔ UI permissions；`feeShareSpringToUi` / `feeShareUiToSpring`（PROFIT→`owner`，SALES/CS/IT→`user`；Profit % 用 `distributeProfitPercentages` 现算remainder，不用 UI 原始值）；`groupToTenantSaveEntry` / `companyToTenantSaveEntry` |
| `pages/domain/DomainPage.jsx` | 列表/删除/续费价摘要走 `domainApi.js`（**不再** `domain_api.php`） |
| `pages/domain/components/DomainFormModal.jsx` | create/update + `syncAllTenantSettings`；编辑回填用 list 行 `companies_full` / `groups_full` |
| `pages/domain/components/DomainFeeModal.jsx` | `list-fee` / `add-fee` |
| `pages/domain/components/CompanySettingsModal.jsx` | Share 账户 `fetchShareAccountsForTenant`；持久化 `update-setting`；Add Account 传 `shareLedgerTenantId` |
| `pages/domain/components/AddAccountModal.jsx` | Share % **Add Account** 全 Spring：复用 `accountListApi` + `currencyApi`；scope = C168 `tenant.id` |

**Spring 契约（Domain）**

| 操作 | API | 说明 |
|------|-----|------|
| List | `POST /api/domain/list` | 扁平 `OwnerTenantDTO[]` → 前端 aggregate 为 owner 行 |
| Add | `POST /api/domain/add` | `DomainDTO` + `Tenant` camelCase（`expirationDate`, `parentGroupCode`） |
| Update 骨架 | `PUT /api/domain/update` | 增删改 tenant 结构；**不含** featureModules / feeShare |
| Update 设置 | `PUT /api/domain/update-setting` | `Tenant`：`featureModules[]`, `feeShareAllocations[]`, `expirationDate`, `code` |
| Delete | `POST /api/domain/delete` | body `{ id }`（owner id） |
| List fee | `POST /api/domain/list-fee` | `data[0]` = `DomainFeeSettingsDTO`（`company_period_prices` / `group_period_prices`） |
| Save fee | `POST /api/domain/add-fee` | 同上 DTO |

**Confirm 流程**：`add`/`update` 骨架成功后，前端对每个 group/company 调 `update-setting` 写入 permissions + Share %。

**Share % 账户 ledger（C168）**

| 项 | 约定 |
|----|------|
| Tenant 解析 | `resolveShareLedgerTenantId(me)` / `resolveShareLedgerTenantCode(me)`（`domainApi.js`） |
| Session 来源 | `SessionUser.tenant_id` / `tenant_code`（**非** `company_id`）；fallback 从 owner companies 找 code=`C168` |
| 列表 | `POST /api/account/list?tenant_id=` → `fetchShareAccountsForTenant` |
| Add Account | `POST /api/account/add` body `UserListDTO`：`scopeTenantId` + `currencyIds[]` |
| 币种 | `POST /api/currency/available?tenant_id=` / `add` / `delete` |
| UI pill | AccountModal 仍显示 “Company” 标签，但 picker `id` = **`tenant.id`**，`company_id` 列 = **tenant code** |

**Domain Confirm 写 Transaction**（`apply_commission_payments_on_domain_save` / Charge on Save）已实现：详见 [`login-to-business-pages.md` §4.5.1](./login-to-business-pages.md#451-domain-confirm-charge-on-save--写-transactions2026-07-20)。开关本身仍只是 UI 本地状态（不落 `tenant` 表），随 Domain Confirm 一次性提交给 `PUT /update-setting`，记账成功与否都不需要显式重置——下次重新拉取数据天然是关闭的。

### 4.3 Admin (User List)

| 文件 | 改动 |
|------|------|
| `pages/userlist/userListApi.js` | `api/userlist/list|get|add|update|delete`；`normalizeAdminListItem` |
| `pages/userlist/UserListPage.jsx` | `resolveListTenantId()`：`company.id` → `tenant_id` |

### 4.4 Account (Member 账号)

| 文件 | 改动 |
|------|------|
| `pages/account/accountListApi.js` | `list/add/update/updateStatus/delete`；link `GET link/list`、`POST link`、`PUT link`、`DELETE link/pair`；`normalizeAccountListItem`、`filterAccountListRows`、`accountRowToEditForm` |
| `pages/account/accountLogic.js` | `fetchMergedAccounts` → Spring 多 tenant merge；`deriveAccountRolesFromRows`；`resolveGroupCodeToTenantId` |
| `pages/account/accountRoutePrefetch.js` | 预热改走 `fetchFilteredAccountListByTenantId` |
| `pages/account/AccountListPage.jsx` | **全页**直调 Spring：列表/CRUD/状态/付款提醒/币种/链接；Edit 自 list 行回填；search/status **客户端**过滤 |

**Spring 契约（Account List）**：

| 操作 | API | 说明 |
|------|-----|------|
| List | `POST /api/account/list?tenant_id=` | `company.id` = `tenant.id`；无 search query → `filterAccountListRows` |
| Add | `POST /api/account/add` | body `UserListDTO` camelCase；`currencyIds[]` 一并写入 |
| Update | `POST /api/account/update` | 含 `currencyIds[]`；**password 省略或留空 → 保留原密码** |
| Status | `POST /api/account/updateStatus` | `{ id, scopeTenantId }` |
| Delete | `POST /api/account/delete` | 须 INACTIVE；多选前端循环 |
| Payment alert | `POST /api/account/update` | 无独立 toggle；toggle 时带当前 `currencyIds` |
| Currencies (modal) | `POST /api/currency/available?tenant_id=&account_id=` | `currencyApi.fetchAvailableCurrencies` |
| Currency CRUD | `POST /api/currency/add|delete` | Setting / modal 创建删除 |
| Currency bulk link | `POST /api/currency/account/linked-accounts-update` | Currency Setting 弹窗 |
| Account link | `GET /api/account/link/list` + `POST/PUT/DELETE` | 使用 **session.tenant_id**；query `tenant_id` 做权限校验 |

**已知缺口**：Spring create/update 仅写 **单个** `scopeTenantId`（无 PHP `company_ids[]` 多租户）；Group list 需 `resolveGroupCodeToTenantId` 将 group code → group tenant.id。

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
| `pages/autorenew/autoRenewLogic.js` | 列表：`api/subscription/auto_renew_api.php` → `api/auto-renew/list`；`reject` → `api/auto-renew/reject`；**`approve` → `api/auto-renew/approve`**（`request_id` + `period`） |
| `pages/autorenew/autoRenewTenantSettings.js` | Comm 打开：`POST /api/domain/list?ownerId=`；费用预览 → `list-fee`；Save → `update-setting`（`commissionOnly`） |
| `pages/autorenew/AutoRenewPage.jsx` | Comm 传 `ownerId`；Approve 只传 `requestId` + `period` |
| `pages/domain/domainApi.js` | `fetchDomainList(ownerId?)` 支持可选 `?ownerId=` |

**Approve 后端（本仓）：** `POST /api/auto-renew/approve` → 复用 `DomainFeeChargeService.chargeDomainFee`（与 Domain Charge on Save 同账）+ 从当前 `expiration_date` 加 period；无 Charge on Save 开关。

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
- **Bank Process List**：列表已 Spring；**写操作 / 国家银行选择 / Accounting Due / 账户弹窗**仍混用 `api/bankprocesses/*`、`api/accounts/*` PHP
- **Member Win/Loss**：`api/member/*`（账户 meta 可复用 `/api/account/list`）
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

### 9.8 Games ↔ Bank Process 页面路由（2026-07-14）

切换公司时需按 **目标 tenant** 的 `has_bank` / `has_game` 决定落在哪张 Process 页。会话仍停在上一公司时，**不能**只用当前 `sessionMe` 的 flag。

| 项 | 约定 |
|----|------|
| Bank-only | `has_bank && !has_game`（兼容 `tenant_has_*` / `company_has_*`） |
| 判定入口 | `resolveTenantIsBankOnly(tenantId, sessionMe)`（`bankProcessHelpers.js`） |
| 同会话 tenant | 直接读 `sessionMe` hint，不调 API |
| 跨 tenant | `POST auth/switch-tenant`（`syncCompanySessionApi`），读返回 `data.has_bank` / `data.has_game` |
| **不再用** | PHP `domain_api.php?action=get_company_permissions`（本地易失败 → 误判为非 Bank） |
| Games → Bank | `ProcessListPage`：bank-only → `/bank-process-list`（无 tenant query） |
| Bank → Games | `useBankProcessListPage`：非 bank-only → `/process-list`（无 tenant query） |
| Tenant 来源 | 会话 `session.tenant_id` + API RequestBody；`stripTenantIdFromUrlSearchParams` 清掉遗留 query |
| 会话刷新 | switch 成功后 `notifyCompanySessionUpdated(syncJson.data)` |

后端无需为路由单独加接口：`SessionUser` 与 `switch-tenant` 已带 `has_game` / `has_bank`。

---

## 10. Bank Process List 专项（2026-07-15）

### 10.1 背景

`bank_process` 表按 **tenant** 建模（`tenant_id`）。列表 API 用 **RequestBody** 传数字 `tenantId`；**不要**在 API query 或 SPA 地址栏写 `?tenant_id=` / `?company_id=`。UI 上 Group / Company pill 仍可用（展示 `tenant_code`），数字 id = `tenant.id`。

### 10.2 列表桥接

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/list`，JSON body = 数字 `tenantId`（**无** query） |
| 前端入口 | `bankProcessListApi.fetchBankProcessListByTenantId` |
| DTO → 行 | `normalizeBankProcessListItemFromSpring` — 优先读 DTO 根字段 `status`（String），再读 `bankProcess.status` |
| Shares | list 经 MyBatis `<collection select="findSharesByBankProcessId">` 嵌套加载；normalize 写入行上 `shares[]`（供 Edit 回填） |
| Status 列表 | DTO 根 `status` 用 `bp_status` 映射为 **String**（避免 nested enum TypeHandler 失败 → null → UI 误显示 ACTIVE） |
| Prefetch | `prefetchBankProcessListPayload`（`processRoutePrefetch.js`） |
| SPA URL | **不写** tenant；`stripTenantIdFromUrlSearchParams` 清掉遗留；tenant 用 session + 页内 state |
| 内部 state 名 | 变量可仍叫 `companyId`（历史命名）= **tenant 数字 id** |
| Group / Company pills | `GET /auth/tenant-accessible` → `fetchOwnerCompaniesAll`；`id`/`tenant_id` = 数字 tenant id，`company_id` = `tenant_code`，`group_id` = `parent_tenant_code` |
| Bank-only 路由 | `resolveTenantIsBankOnly`（session `tenant_has_*` / switch-tenant `has_bank`·`has_game`）；**不再**调 PHP `domain_api` |

### 10.3 Country / Bank catalog（前后端，2026-07-15）

| 项 | 约定 |
|----|------|
| List countries | `POST /api/bank-country-option/list-country`，body = 数字 `tenantId` |
| List banks | `POST /api/bank-country-option/list-bank-option`，body `{ tenantId, countryId }` |
| Add country | `POST /api/bank-country-option/insert-country`，body `{ tenantId, code }` |
| Add bank | `POST /api/bank-country-option/insert-bank-option`，body `{ tenantId, countryId, name }` |
| Delete | `delete-country` / `delete-bank-option`（body 用 `id` + `tenantId`，bank 还要 `countryId`） |
| 前端入口 | `bankCountryOptionApi.js`；`useBankProcessListPage` 弹窗 Add/Remove/List 已直调 Spring |
| 对齐方向 | **前端适配 Spring**（`id`/`code`/`name`/`tenantId`）；不再传 `company_id` / FormData PHP |
| Selected chips | 仅本地 UI 过滤下拉；**无** PHP `save_selected_*`；Spring catalog = tenant 全量 |

### 10.3.1 Bank Process Add（前后端，2026-07-15）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/add-bank-process`（JSON body，无 query） |
| 前端 | `bankProcessListApi.addBankProcess` + `buildAddBankProcessRequest` |
| 字段 | `tenantId, countryId, bankOptionId, cardOwner, cardOwnerType, frequency, …, shares[]` |
| Frequency | UI `1st_of_every_month/monthly/once/day/week` → Spring `FIRST_OF_EVERY_MONTH/MONTHLY/ONCE/DAY/WEEK` |
| 不再走 | `api/processes/addprocess_api.php` |

### 10.3.2 Bank Process Update / Edit（前后端，2026-07-16）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/update-bank-process`（JSON body，无 query） |
| Service | `updateBankProcessDetails`：更新可变字段 + **delete-all shares 再 batch insert**（同一 `@Transactional`） |
| 不可变 | **不更新** `countryId` / `bankOptionId` / `cardOwner` / `cardOwnerType`（UI Edit 只读；后端以 DB 为准） |
| 可变 | `dayStart` / `dayEnd` / `frequency`、supplier·customer·company account+price、`contract` / `insurancePrice` / `sop` / `remark`、`shares[]` |
| 前端 | `buildUpdateBankProcessRequest` + `updateBankProcess`（只发 `id` + `tenantId` + 可变字段 + `shares`） |
| Edit 打开 | **无 get API**；`openEdit` 用 list 行 `bankProcessListRowToEditForm(row, accounts)` 本地回填（对齐 Process list 回填） |
| 不再走 | `processlist_api.php?action=get_process` / `action=update_process` |

### 10.3.3 Bank Process Status（前后端，2026-07-16）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/update-status`，body `{ id, tenantId, status }` |
| Status 枚举 | Spring 统一字段：`ACTIVE` / `INACTIVE` / `OFFICIAL` / `E_INVOICE` / `BLOCK`（**不是** Process 的 ACTIVE↔INACTIVE toggle） |
| 不可写 | `WAITING`（list 可读；update 拒绝） |
| Service | `updateBankProcessStatus(id, tenantId, status)` → `updateStatus` |
| 前端 | `bankProcessListApi.updateBankProcessStatus`；`BankProcessStatusControl` 菜单值直接当 `status` 一次提交 |
| List → UI | `splitSpringBankProcessStatus`：统一枚举 → 行上 `status` + `issue_flag`（过滤 chips 仍用） |
| 缓存 | status 变更后 `invalidateBankProcessListRouteCache` + 清页内 list cache；`bankProcessRowsFingerprint` 含 status/issue_flag（避免 silent refetch 因 id 相同而保留旧 ACTIVE） |
| 不再走 | `toggle_process_status_api.php` / `update_bank_issue_flag_api.php` |

### 10.3.4 Bank Process Delete（前后端，2026-07-16）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/delete-bank-process`，body `{ id, tenantId }`（单条） |
| Service | `deleteBankProcess`：须 `INACTIVE`；同一 `@Transactional` 先 `deleteBankProcessShareBatch` 再 `deleteBankProcess` |
| Shares | 显式 batch delete；表上亦有 `ON DELETE CASCADE` |
| 前端 | `bankProcessListApi.deleteBankProcess`；多选 `for … of selectedIds` 循环单条；成功后清 list/warm cache + `fetchRows({ forceReplace: true })` |
| 不再走 | `api/processes/delete_processes_api.php` |

### 10.3.5 Bank Process Remark（前后端，2026-07-16）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/update-remark`，body `{ id, tenantId, remark }` |
| Service | `updateBankProcessRemark` → 窄更新 `remark` + `updated_by` / `updated_at`（不走整单 `update-bank-process`） |
| 前端 | `bankProcessListApi.updateBankProcessRemark`；行内 Remark 弹窗 `saveRemarkModal`；空串 → `remark: null` |
| 不再走 | `api/processes/update_bank_remark_api.php` |

### 10.3.6 Bank Process Resend（前后端，2026-07-17）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/resend`，body AccountingDueDTO：`{ tenantId, bankProcessId, dayStart, dayEnd?, frequency }` |
| Frequency | Spring 枚举：`FIRST_OF_EVERY_MONTH` / `MONTHLY` / …（前端 `toSpringBankProcessFrequency`） |
| Phase 1 | 全部频率已实现：`FIRST_OF_EVERY_MONTH`（dayStart+dayEnd）、`MONTHLY`（dayStart～+1月）、`ONCE`/`DAY`（单日）、`WEEK`（dayStart～+6天）；开放补单写 `resend_schedule_*`；同 dayStart 拒、换 dayStart 覆盖；补单不按日期过滤 |
| 前端 | `bankProcessListApi.resendBankProcess` + `buildResendBankProcessRequest`；`useBankProcessListPage.resendAccountingDue` |
| 锁检查 | Phase 1 无 Post 同日锁 API；开放重复用 Inbox `RESEND_CONSOLIDATED` / list `resend_schedule_day_start` 客户端判断 |
| 不再走 | `api/bankprocess_maintenance/resend_accounting_due_api.php` |

### 10.4 尚未 Spring（仍 PHP）

- （Bank Process Accounting Due post-to-transaction 已迁 Spring，见 10.3.7）

### 10.3.7 Accounting Due Post to Transaction（2026-07-20）

| 项 | 约定 |
|----|------|
| API | `POST /api/bank-process/accounting-due/post`，body 与 skip 同形 |
| 1st of every month | `FIRST_MONTH` / `PARTIAL_FIRST_MONTH` / `FULL_MONTH` / `DAY_END_TAIL`；Partial/Tail 按天数比例 |
| Monthly | `MONTHLY` only；**全额** |
| Week | `WEEKLY` only；**全额**；Description `WEEK (dd/MM/yyyy - dd/MM/yyyy) @ amt \| bank` |
| Day | `DAILY` only；**全额**；Description `DAY (dd/MM/yyyy) @ amt \| bank` |
| Once | `ONCE_ONE_OFF`；**全额**；Description `ONCE (dd/MM/yyyy) @ amt \| bank`；Post/Skip 后 → `INACTIVE` |
| 1+1 / 1+2 / 1+3 | 非 ACTIVE Post → 赔款 ×1/×2/×3 + `COMPENSATION …`；Case B 额外 `periodType=COMPENSATION`（锚点 dayStart，txn=today） |
| 金额 | Buy→Supplier(WIN)、Sell→Customer(LOSE)、Profit→Company(WIN)；PS 可选 |
| 分层 | `TransactionDao` 写 `transactions`；`AccountingDueService` 编排；ledger 仍走 `AccountingDueDao` |
| 前端 | `bankProcessListApi.postAccountingDue`；`useBankProcessListPage.postAccountingToTransaction` |
| 不再走 | `api/processes/process_post_to_transaction_api.php`（上述范围） |
| Resend 进账 | `periodType=RESEND_CONSOLIDATED`；窗口=用户补单日期；1st 按月切段加总金额、一律 PRORATED desc；Once 补单不改 status；Post 后清 `resend_schedule_*` |

**Bank Process → Add Account 弹窗（2026-07-15）**：已走 Spring  
`POST /api/account/add|update`（`accountListApi`）、`/api/currency/available|add|delete`、`POST /api/account/list` 刷新下拉；`scopeTenantId` = 页内 tenant 数字 id；不再走 `addaccountapi.php` / `account_company_api` / `account_currency_api`。

---

## 11. Transaction 列表（BP Win/Loss + Domain Payment Cr/Dr）+ 手动转账 Submit（2026-07-22）

> **范围**：Transaction Payment 主列表展示 + 右侧表单 **PAYMENT / CLAIM / CLEAR / CONTRA** 提交  
> 1. **Bank Process** Post（`bank_process_posted_id IS NOT NULL`，WIN/LOSE → **Win/Loss**）  
> 2. **Domain Fee / Payment**（`PAYMENT` 且 `bank_process_posted_id IS NULL`，含 Domain Confirm Charge on Save + Auto Renew Approve → **Cr/Dr**）  
> 3. **手动 PAYMENT / CLAIM / CLEAR / CONTRA Submit**（→ **Cr/Dr**）  
> 4. **手动 ADJUSTMENT Submit**（→ **Win/Loss**；仅 To Account，signed amount）  
> **不含**：RATE、Type Search 过滤开关、**Contra Inbox 审批**、其他 type Submit。  
> **正负布局**：余额为正 → 左表；为负 → 右表（与既有一致）。  
> **NET PROFIT（C168→C168，净 0.00）**：Capture Date **当期**仍展示（`hasCrDrInPeriod`）；隔日仅历史净 0、无当期动账的 Domain-only 行不展示。

### 11.1 API 分工一览

| 页面能力 | 旧 PHP | Spring（复用 / 新建） | 说明 |
|----------|--------|----------------------|------|
| 公司 / Group pill | `get_owner_companies_api.php` | ✅ `GET /auth/tenant-accessible` | 已有 `tenantAccessibleApi.js` / `fetchOwnerCompaniesAll` |
| 账户下拉 / 列表 meta | `get_accounts_api.php` | ✅ `POST /api/account/list?tenant_id=` | 已有 `accountListApi.fetchAccountListByTenantId`；前端加 `normalizeTransactionAccountOption` |
| 币种 pill / 列 | `get_company_currencies_api.php` | ✅ `POST /api/currency/list?tenant_id=` | 已有 `currencyApi.fetchCurrencyListByTenantId` + `normalizeCurrencyRow` |
| Group scope 币种 | `get_scope_account_currencies_api.php` | ⚠️ v1 仍用 `currency/list`（单 tenant） | Group 聚合列后续再扩 |
| Category 下拉 | `get_categories_api.php` | ⚠️ v1 **前端**从 account list 去重 `role` | 新 schema 无独立 `role` 字典表；顺序沿用旧 priority 常量 |
| 用户币种排序 | `user_currency_order_api.php` | ⚠️ v1 **localStorage** | `currencyDisplayOrder.js`；新 schema 用 `account_currency.sort_order`（按账户链，非用户级） |
| **主列表 Search** | `search_api.php` | ✅ `POST /api/transaction/search` | BP WIN/LOSE + Domain PAYMENT Cr/Dr 合并 |
| Payment History | `history_api.php` | ✅ `POST /api/transaction/history` | BF（BP+Domain）+ 明细按 `created_at` 升序；BP→Win/Loss（Id Product=`cardOwner`），Domain→Cr/Dr；Id Product=`PAYMENT`/`COMMISSION`/`PROFIT`；**C168 仅展示 NET PROFIT，Cr/Dr=净利润金额** |
| **手动 PAYMENT / CLAIM / CLEAR / CONTRA Submit** | `submit_api.php` | ✅ `POST /api/transaction/submit` | Cr/Dr transfer types；即时 `APPROVED` |
| **手动 ADJUSTMENT Submit** | `submit_api.php` | ✅ `POST /api/transaction/submit` | 仅 `toAccountId`；signed amount → Win/Loss |
| Contra Inbox / 审批 | `contra_*` | — | Submit 已 Spring（即时生效）；Inbox 仍 PHP |

### 11.2 Meta 层复用约定

**租户 scope**

- Query / body 统一 `tenant_id` = UI 公司 pill 的数字 id（= `tenant.id`）。
- v1 仅 **单 company tenant**；`view_group` / `group_aggregate` / `subsidiary_accounts_only` 先忽略（PHP 仍传，Spring 可忽略）。

**账户 — 复用 `POST /api/account/list`**

```javascript
// 建议：Count-frontend/src/pages/transaction/lib/transactionAccountHelpers.js
import { fetchAccountListByTenantId, normalizeAccountListItem } from "../../account/accountListApi.js";

export function normalizeTransactionAccountOption(row) {
  const a = normalizeAccountListItem(row);
  if (!a) return null;
  const code = String(a.account_id || "").trim();
  const name = String(a.name || "").trim();
  return {
    id: a.id,
    account_id: code,
    name,
    display_text: name ? `${code} (${name})` : code,
    role: String(a.role || "").toUpperCase(),
    currency: null, // v1：列币种由 search 行 + currency/list 决定，非账户首币
    status: a.status,
  };
}
```

- 客户端过滤：`status=active`、可选 `role`（Category）— 与 PHP `get_accounts_api` 行为对齐。
- 不再走 `get_accounts_api.php`；**勿**为 Transaction 单独复制 Account CRUD。

**币种 — 复用 `POST /api/currency/list`**

```javascript
import { fetchCurrencyListByTenantId, normalizeCurrencyRow } from "../../../utils/api/currencyApi.js";
// rows → normalizeCurrencyRow → orderCurrencyRows(localStorage order)
```

- 返回 UI 需 `{ code }`（`normalizeCurrencyRow` 已提供 `id` + `code`）。
- 不再走 `get_company_currencies_api.php` / `get_scope_account_currencies_api.php`（v1）。

**Category — v1 无新 API**

- 从 `fetchAccountListByTenantId` 结果取 `role` 去重 + 固定 priority（CAPITAL, BANK, …）排序。
- 过滤 Search 时传 `categories[]=BANK` → 后端按 `account.role` 过滤。

### 11.3 Search API 契约（已实现；2026-07-22 含 Domain Payment）

**`POST /api/transaction/search`**

Request body（camelCase）：

```json
{
  "tenantId": 95,
  "dateFrom": "01/07/2026",
  "dateTo": "20/07/2026",
  "currencyCodes": ["MYR"],
  "categories": ["BANK", "SUPPLIER"]
}
```

合并规则：

- BP：`WIN/LOSE` + `bank_process_posted_id` → `winLoss` / BF
- Domain/Payment：`PAYMENT` + `bank_process_posted_id IS NULL` → `crDr` / BF（To −amount，From +amount）
- `balance = bf + winLoss + crDr`
- Domain-only 且 BF/CrDr 全 0、当期无 Payment 动账 → 不返回（隔日隐藏纯 NET PROFIT 0.00）
- 当期有 Payment 动账（含 NET PROFIT 自转）→ `hasCrDrInPeriod=true`，即使 `crDr=0.00` 仍返回

Response `data`：

```json
{
  "rows": [
    {
      "accountId": 12,
      "accountCode": "SUP001",
      "accountName": "Supplier A",
      "role": "SUPPLIER",
      "currencyCode": "MYR",
      "bf": "0.00",
      "winLoss": "1000.00",
      "crDr": "0.00",
      "balance": "1000.00",
      "hasWinLossInPeriod": true
    }
  ],
  "totals": { "bf": "0.00", "winLoss": "1000.00", "crDr": "0.00", "balance": "1000.00" },
  "activeCurrencyCodes": ["MYR"]
}
```

前端 `transactionSearchNormalize.js` 将 `rows` 拆成 `left_table` / `right_table`（按 balance 正负），表格层无需改。

**Meta 已切 Spring（同任务）**

| 旧 PHP | 现前端 |
|--------|--------|
| `get_accounts_api.php` | `fetchAccountListByTenantId` + `transactionAccountHelpers` |
| `get_company_currencies_api.php` | `fetchCurrencyListByTenantId` |
| `get_categories_api.php` | 固定 priority 列表 `deriveCategoryList()` |
| `user_currency_order_api.php` | localStorage only |

Type Search / Capture-only / show 0 balance 等 **v1 未实现**；Search 固定走 Spring `POST /api/transaction/search`。

### 11.6 Payment History API（已实现 2026-07-20）

**`POST /api/transaction/history`**

Request body（camelCase）：

```json
{
  "tenantId": 95,
  "accountId": 12,
  "dateFrom": "01/07/2026",
  "dateTo": "20/07/2026",
  "currencyCodes": ["MYR"]
}
```

- `tenantId` = UI `company.id`；`accountId` = `account.id`（Payment History scope 的 `account_db_id`）
- `currencyCodes` 空数组 = 该账户区间内全部币种
- 数据源：**BP** `WIN`/`LOSE` + **手动 ADJUSTMENT** + **Domain / 手动转账** `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`（`bank_process_posted_id IS NULL`），均 `APPROVED`

**手动 PAYMENT / CLAIM / CLEAR / CONTRA History 展示**

| Type | 当前查看账户 | Description | Id Product |
|------|-------------|-------------|------------|
| PAYMENT | 收款方（From） | `PAYMENT TO {付款方}` | `PAYMENT` |
| PAYMENT | 付款方（To） | `PAYMENT FROM {收款方}` | `PAYMENT` |
| CLAIM | 收款方（From） | `CLAIM TO {付款方}` | `CLAIM` |
| CLAIM | 付款方（To） | `CLAIM FROM {收款方}` | `CLAIM` |
| CLEAR | 收款方（From） | `CLEAR TO {付款方}` | `CLEAR` |
| CLEAR | 付款方（To） | `CLEAR FROM {收款方}` | `CLEAR` |
| CONTRA | 收款方（From） | `CONTRA TO {付款方}` | `CONTRA` |
| CONTRA | 付款方（To） | `CONTRA FROM {收款方}` | `CONTRA` |

**手动 ADJUSTMENT History 展示**

| 项 | 值 |
|----|-----|
| 账户 | 仅 **To Account**（收款方） |
| Win/Loss | signed `amount`（正=加，负=减） |
| Cr/Dr | `0.00` |
| Description | `ADJUSTMENT - WIN/LOSS` |
| Id Product | `ADJUSTMENT` |

- DB 写入时即存 `description = ADJUSTMENT - WIN/LOSS`；Search 合并进 BP Win/Loss 路径（`aggregateManualAdjustmentWinLoss`）
- Domain Fee 行仍用库内 `description`（`PAY DOMAIN FEE` / `* COMMISSION` / `NET PROFIT`）→ Id Product 规则不变

Response `data`：

```json
{
  "account": { "id": 12, "accountId": "BKCOM", "name": "BK COMPANY ACC" },
  "dateRange": { "from": "01/07/2026", "to": "20/07/2026" },
  "history": [
    {
      "rowType": "bf",
      "date": "01-07-2026",
      "currency": "MYR",
      "balance": "0.00",
      "description": "OPENING BALANCE"
    },
    {
      "id": 101,
      "isBankProcessTransaction": true,
      "cardOwner": "TRAVELMINI SDN BHD",
      "currency": "MYR",
      "winLoss": "1000.00",
      "crDr": "0.00",
      "balance": "1000.00",
      "description": "...",
      "createdBy": "admin1"
    }
  ]
}
```

**排序与余额**

| 项 | 约定 |
|----|------|
| BF 行 | 每个币种一行 `rowType=bf`，排在明细前 |
| 明细排序 | `transactions.created_at ASC, id ASC`（最早在上） |
| `winLoss` | WIN 正、LOSE 负；BP v1 `crDr=0.00` |
| `balance` | 按币种逐行滚动（含 BF） |

前端 `transactionHistoryNormalize.js` → `getHistory()`；`TransactionHistoryTable` 无需改列。

**v1 不做（History）**：`pure_type_search`；Member PDF export（仍 `history_api.php`）。

### 11.7 手动 PAYMENT / CLAIM / CLEAR / CONTRA / ADJUSTMENT Submit API（已实现 2026-07-22）

**`POST /api/transaction/submit`**

**账户方向（与 Domain Fee / Search Cr/Dr 一致）**

| UI / 请求字段 | DB `transactions` | 业务含义 | Cr/Dr 符号 |
|---------------|-------------------|----------|------------|
| **To Account** / `toAccountId` | `account_id` | 付款方（给钱） | **−amount** |
| **From Account** / `fromAccountId` | `from_account_id` | 收款方（拿钱） | **+amount** |

Request body（camelCase）：

```json
{
  "tenantId": 95,
  "transactionType": "PAYMENT",
  "transactionDate": "22/07/2026",
  "toAccountId": 12,
  "fromAccountId": 8,
  "currencyCode": "MYR",
  "amount": 1000.00,
  "remark": ""
}
```

| 字段 | 说明 |
|------|------|
| `tenantId` | UI 公司 pill 的 `tenant.id` |
| `transactionType` | 可省略，默认 `PAYMENT`；transfer：`PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`；或 `ADJUSTMENT` |
| `transactionDate` | `dd/MM/yyyy` 或 `yyyy-MM-dd`；可省略 → 服务器当天 |
| `toAccountId` / `fromAccountId` | `account.id`；必须不同 |
| `currencyId` 或 `currencyCode` | 二选一；须属于该 tenant |
| `amount` | 正数，2 位小数 |
| `remark` | 可选；写入 `transactions.remark` |

写入规则：

- `transaction_type = PAYMENT`
- `approval_status = APPROVED`（即时生效，无 Contra 式 pending）
- `bank_process_posted_id IS NULL`
- `created_by` / `approved_by` = 当前 session `login_id`

校验：

- 已登录；`read_only` 用户拒绝提交
- To / From 账户存在、`ACTIVE`、属于 tenant
- 两账户均在 `account_currency` 中启用所选币别

Response `data`：

```json
{
  "id": 123,
  "transactionType": "PAYMENT",
  "tenantId": 95,
  "toAccountId": 12,
  "fromAccountId": 8,
  "currencyCode": "MYR",
  "amount": "1000.00",
  "transactionDate": "22/07/2026",
  "remark": ""
}
```

提交成功后 **无需改 Search/History** — 与 Domain Fee 相同路径，自动计入 Cr/Dr。

**后端文件**

| 文件 | 说明 |
|------|------|
| `controller/TransactionController.java` | `POST /submit` |
| `service/TransactionSubmitService.java` + `impl/TransactionSubmitServiceImpl.java` | 校验 + insert |
| `dto/TransactionDTO.java` | `SubmitRequest` / `SubmitResult` |
| `dao/TransactionDao.java` + `TransactionMapper.xml` | 复用既有 `insert` |

**前端**

**ADJUSTMENT 请求体（仅 To）**

```json
{
  "tenantId": 95,
  "transactionType": "ADJUSTMENT",
  "transactionDate": "22/07/2026",
  "toAccountId": 12,
  "currencyCode": "MYR",
  "amount": 100.00,
  "remark": ""
}
```

- 无 `fromAccountId`；`amount` 可正可负，**不可为 0**
- 写入：`description = ADJUSTMENT - WIN/LOSS`；`from_account_id = NULL`
- Search / History：**Win/Loss**（非 Cr/Dr）

- `transactionApi.js`：`submitTransaction` — `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`/`ADJUSTMENT` → Spring JSON；RATE 等仍 `submit_api.php`
- `transactionSubmitNormalize.js`：`buildSpringSubmitRequest` / `normalizeSpringSubmitResponse`（旧 snake_case payload ↔ camelCase Spring）
- `useTransactionForm.js`：**无需改**；仍组 legacy payload（`account_id`/`from_account_id`/`currency`/`sms`）

Legacy payload → Spring 映射：

| 旧 Form / payload | Spring body |
|-------------------|-------------|
| `company_id`（scope） | `tenantId` |
| `account_id` | `toAccountId` |
| `from_account_id` | `fromAccountId` |
| `currency` | `currencyCode` |
| `sms` | `remark` |
| `transaction_date` | `transactionDate` |
| `amount` | `amount` |

### 11.8 前端改动（History）

| 文件 | 改动 |
|------|------|
| `transactionHistoryNormalize.js` | Spring `HistoryResult` → 表格 snake_case 行 |
| `transactionApi.js` | `getHistory` → `POST /api/transaction/history` |

### 11.4 前端改动清单

| 文件 | 改动 |
|------|------|
| `transactionApi.js` | Meta → Spring；Search → `/search`；History → `/history`；**PAYMENT/CLAIM/CLEAR/CONTRA/ADJUSTMENT Submit → `/submit`** |
| `transactionSubmitNormalize.js` | **新建** — Submit request/response 适配 |
| `transactionHistoryNormalize.js` | Spring history → BF + 明细行 |
| `transactionSearchNormalize.js` | Spring `rows` → `left_table` / `right_table`（balance 正负分列） |
| `transactionAccountHelpers.js` | 账户 Meta normalize + Category priority |
| `useTransactionSearch.js` | v1 跳过 zero-balance / payment / capture 展示过滤，直接渲染 API 行 |

### 11.5 本期明确不做

- Data Capture 行合并进 Search
- RATE / `transaction_entry`
- **Contra Inbox** 审批、PROFIT 等手动 type（仍 PHP 或尚未实现）
- `type_account_search` / `type_transaction_search`
- Member / PDF export 的 `history_api.php`（主 Payment History 页已 Spring）
- `user_currency_order_api` 服务端持久化

---

## 12. 彻底去除 UPLINE 账户角色（2026-07-20）

> **背景**：旧版 PHP 账户 role 曾使用 `UPLINE` 表示供应商；UI 选项已统一为 **`SUPPLIER`**。  
> **目标**：代码、样式、API 校验与数据库中 **不再保留 `UPLINE` 作为 account.role**；历史脏数据一次性迁移为 `SUPPLIER`。

### 12.1 范围说明

| 属于本项（account.role） | **不属于**本项 |
|--------------------------|----------------|
| `account.role` 字段读写与展示 | Data Capture 报表里的 **「Upline Payment」** 段落（Citibet 粘贴解析术语） |
| Category 下拉、role badge CSS、Bank Process 选账户 role 列表 | 业务英文单词 upline（上下级关系描述） |

### 12.2 数据库迁移（部署必跑）

脚本：[`backend/src/main/resources/sql/migrate_upline_role_to_supplier.sql`](../backend/src/main/resources/sql/migrate_upline_role_to_supplier.sql)

```sql
UPDATE `account`
SET `role` = 'SUPPLIER'
WHERE UPPER(TRIM(`role`)) = 'UPLINE';
```

- 每个环境 **执行一次** 即可。
- 迁移后：`SELECT DISTINCT role FROM account WHERE UPPER(role) LIKE '%UPLINE%'` 应无结果。

### 12.3 后端约定

| 项 | 约定 |
|----|------|
| 合法 role 白名单 | `UserServiceImpl.ALLOWED_ACCOUNT_LEDGER_ROLES` **不含** `UPLINE` |
| 写入兼容（短期） | `POST/PUT /api/account/add|update` 若 body 仍传 `role=UPLINE`，**normalize 为 `SUPPLIER`** 再入库（防旧客户端） |
| 读取 | 不再做 `UPLINE` → `SUPPLIER` 映射；依赖迁移保证 DB 干净 |
| Transaction Search | `TransactionSearchServiceImpl` 直接返回 `account.role`（大写 trim） |

### 12.4 前端约定

| 区域 | 改动 |
|------|------|
| `accountLogic.js` | `ROLE_PRIORITY` / `getOrderedRoles` 仅 `SUPPLIER`，无 UPLINE 合并逻辑 |
| `bankProcessHelpers.js` | `BANK_PICK_ACCOUNT_ROLES` 去掉 `UPLINE` |
| `transactionPaymentLogic.js` | role CSS class：`supplier` → `transaction-role-supplier` |
| `transactionAccountHelpers.js` | Category 列表无 UPLINE 过滤/映射 |
| `AddAccountModal.jsx`（Domain Share %） | 默认 role：`profit→PROFIT`，`sales/cs/it→STAFF`；scope 为 C168 `tenant.id` |
| `accountTranslate.js` | 删除 `upline` i18n key（保留 `supplier`） |
| CSS | `account-role-supplier`、`transaction-role-supplier`、`category-tag[data-category-value="SUPPLIER"]`；**删除** `*-upline` / `UPLINE` category 规则 |

### 12.5 验证清单

1. 跑完 SQL 迁移。
2. Account List：role 列与编辑下拉 **只有 SUPPLIER**，无 UPLINE 文案。
3. Bank Process Add/Edit：Supplier 账户 picker 的 role 过滤正常。
4. Transaction 列表：SUPPLIER 账户行 role 色块为 supplier 样式。
5. 新建账户选 Supplier → DB `account.role = 'SUPPLIER'`。
6. （可选）旧客户端若仍 POST `role=UPLINE` → 库中仍为 `SUPPLIER`。

### 12.6 勿再引入 UPLINE

- 新代码 **禁止** 在 account role 枚举、Category、CSS class、API 文档示例中使用 `UPLINE`。
- 若从旧 PHP 文档/脚本复制 role 列表，先替换为 `SUPPLIER` 再合入。

---

## 13. Account List 全量 Spring（2026-07-20）

> **原则**：以 Spring `UserListDTO` / `UserLink` / `currencyApi` 为准；**不再**调用 `api/accounts/*` PHP。  
> Edit 打开 **不** 调 `getaccount_api.php`，与 Process List 相同：用当前 list 行 + `/api/currency/available` 回填。

### 13.1 列表与过滤

| 项 | 约定 |
|----|------|
| 单公司 | `fetchFilteredAccountListByTenantId(company.id, filters)` |
| Group-only | `resolveGroupCodeToTenantId(selectedGroup)` → 同上 |
| All 模式 | `fetchMergedAccountLists({ tenantIds })` 去重后 `filterAccountListRows` |
| 行字段 | `normalizeAccountListItem` → snake_case；`scope_tenant_id` 来自 `UserListDTO.scopeTenantId` |

### 13.2 写操作 scope

删除 / 状态 / 更新均带 **`scopeTenantId`**（行上 `scope_tenant_id` 或当前 pill `company.id`）。  
Add/Update body 见 §4.4；校验错误直接展示 Spring `message`。

### 13.3 币种与链接

- Modal 币种：`fetchAvailableCurrencies`；保存时 `currencyIds` 随 add/update 一次提交。
- Currency Setting：`fetchLinkedAccountsByCurrency` + `bulkUpdateAccountCurrency`。
- Link modal：`fetchAccountLinkedAccounts` + `linkAccountPair` / `unlinkAccountPair` / `updateAccountLinkPair`。

### 13.4 Roles meta

无 Spring roles 接口。Add/Edit modal 使用 **`ACCOUNT_LEDGER_ROLES`**（与后端 `UserServiceImpl.ALLOWED_ACCOUNT_LEDGER_ROLES` 一致）作为完整下拉选项；`deriveAccountRolesFromRows` 仍用于列表排序等，**不再**限制 modal 只显示 tenant 内已存在的 role。

---

## 14. Domain Share % Add Account tenant 对齐（2026-07-20）

> **背景**：Share % 的账户始终建在 **C168 ledger tenant**（非正在编辑的 domain tenant）。  
> **问题**：弹窗仍读 `me.company_id`（Spring session 为 `tenant_id`）→ `tenantId` 为空 → “Please select a company first”。  
> **原则**：与 §13 Account List 相同——**picker `company.id` = `tenant.id`**，写操作带 **`scopeTenantId`**。

### 14.1 Tenant 解析

| 函数 | 文件 | 说明 |
|------|------|------|
| `resolveShareLedgerTenantId(me)` | `domainApi.js` | 优先 `SessionUser.tenant_id`（当前为 C168）；否则 owner companies 中 code=`C168` 的 `id` |
| `resolveShareLedgerTenantCode(me)` | `domainApi.js` | 展示用 tenant code，默认 `C168` |
| `getSessionTenantId(me)` | `sessionTenant.js` | `tenant_id ?? company_id`（兼容旧字段） |

**调用链**：`DomainPage` / `AutoRenewPage` → `DomainFormModal` → `CompanySettingsModal` → `AddAccountModal`  
Props 命名：**`shareLedgerTenantId` / `shareLedgerTenantCode`**（不再使用 `sessionCompanyId`）。

### 14.2 Add Account API 映射

| UI 动作 | Spring API | Body / Query |
|---------|------------|--------------|
| 打开弹窗 — 角色 | （无 API） | 客户端 `getAccountModalOrderedRoles` → 完整 `ACCOUNT_LEDGER_ROLES`（12 项，对齐后端白名单） |
| 打开弹窗 — 币种 | `POST /api/currency/available?tenant_id=` | — |
| 创建币种 | `POST /api/currency/add` | `{ tenantId, code }` |
| 删除币种 | `POST /api/currency/delete?id=&tenantId=` | — |
| 保存账号 | `POST /api/account/add` | `UserListDTO` camelCase（见下） |

**`POST /api/account/add` body（与 Account List 一致）**

```json
{
  "accountId": "STAFF01",
  "name": "Sales A",
  "role": "STAFF",
  "password": "…",
  "remark": "",
  "paymentAlert": 0,
  "alertDay": null,
  "alertSpecificDate": null,
  "alertAmount": null,
  "scopeTenantId": 123,
  "currencyIds": [1, 2]
}
```

- `scopeTenantId` = C168 **`tenant.id`**（数字）。
- **不再**调用 PHP `addaccountapi.php` / `account_currency_api.php` / `account_company_api.php`。
- Spring create **单 tenant**；Share % 场景固定 C168，无 `company_ids[]` 多选。

### 14.3 Share 卡片 role → account.role

| Share % UI 卡片 | 新建账号默认 `role` | Share picker 过滤 |
|-----------------|---------------------|-------------------|
| Profit | `PROFIT` | `PROFIT` 或 account_id=`PROFIT` |
| Sales / CS / IT | `STAFF` | `STAFF` / `AGENT` |

Fee share 持久化仍用 `feeShareUiToSpring` 的 `shareType`（`SALES`/`CS`/`IT`/`PROFIT`）——与 **account.role** 是不同字段。

### 14.4 改动文件

| 文件 | 改动 |
|------|------|
| `domainApi.js` | `resolveShareLedgerTenantId` / `resolveShareLedgerTenantCode` |
| `DomainPage.jsx` | 传 `shareLedgerTenantId/Code`（`resolveShareLedger*`） |
| `AutoRenewPage.jsx` | 同上（共用 `CompanySettingsModal`） |
| `DomainFormModal.jsx` | props 重命名并向下传递 |
| `CompanySettingsModal.jsx` | Share 列表 + Add Account 使用 `shareLedgerTenantId` |
| `AddAccountModal.jsx` | props `tenantId`/`tenantCode`；全 Spring account/currency API |

### 14.5 验证清单

1. Domain → Edit → Company Settings → Share % → **+** 打开 Add Account：**无** “Please select a company first”。
2. Company pill 预选 **C168**（或当前 C168 tenant code）。
3. Role 下拉含 `STAFF`/`PROFIT` 等；Profit 卡片默认 `PROFIT`，Sales 默认 `STAFF`。
4. 保存后 `POST /api/account/add` 成功；Share % 下拉刷新可见新账号。
5. Network 面板：**无** `api/accounts/*.php` 请求。

### 14.6 Share % owner_type / Profit percentage 业务对齐（2026-07-20）

> **背景**：Save 时 `feeShareUiToSpring` 之前把所有卡片都写成 `ownerType: "owner"`，且 Profit 卡片没有 % 输入框，导致存入 `tenant_fee_share_allocation` 的 Profit `percentage` 永远是 `0`。按用户业务定义修正：

- **Profit** = C168 从 Domain fee 里留存的部分 → `ownerType: "owner"`。
- **Sales / CS / IT** = 该公司内部人员从 C168 应付款中抽取的 Commission → `ownerType: "user"`。
- **Profit 的 percentage** = `100 - (sales% + cs% + it%)`，多个 Profit 账号时按剩余份额均分；由新增的 `domainHelpers.distributeProfitPercentages(fsa)` 在 Save 时现算，`feeShareUiToSpring` 按行 index 对齐写入，不再依赖 UI 状态里从未被填写过的 `percentage` 字段。
- 后端 `DomainServiceImpl.validateAndPrepareFeeShareRows` 新增一致性校验：`PROFIT` 行的 `owner_type` 必须是 `owner`；`SALES/CS/IT` 行必须是 `user`；不满足则 `BusinessException`（`owner_type: "group"` 仍保留用于未来跨 tenant 分账，不受此规则约束）。
- **改动文件**：`Count-frontend/src/pages/domain/domainHelpers.js`（新增 `distributeProfitPercentages`，`computeShareTotals` 复用，重写 `feeShareUiToSpring`）；`backend/src/main/java/com/eazycount/service/impl/DomainServiceImpl.java`（`validateAndPrepareFeeShareRows`）。
- **遗留待办**（未实现，仅存分配比例）：实际扣款/入账逻辑——从公司账户扣钱、按比例写入 Sales/CS/IT 及 C168 Profit 的交易台账。这是用户描述的完整业务闭环里尚未开发的部分。

---

## 附录：快速文件索引

```
Count-frontend/src/utils/core/apiUrl.js          # PHP → Spring URL 重写表
Count-frontend/src/utils/company/tenantAccessibleApi.js
Count-frontend/src/utils/auth/sessionTenant.js
Count-frontend/src/pages/domain/domainApi.js
Count-frontend/src/pages/domain/components/AddAccountModal.jsx
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
Count-frontend/src/pages/bankprocesslist/lib/bankProcessHelpers.js  # list normalize + Edit 回填 + status split
Count-frontend/src/pages/bankprocesslist/bankProcessListApi.js      # list + add/update/update-status/delete
Count-frontend/src/pages/bankprocesslist/bankCountryOptionApi.js   # POST /api/bank-country-option/* (tenantId body)
Count-frontend/src/pages/bankprocesslist/components/BankProcessStatusControl.jsx  # Spring update-status
Count-frontend/src/pages/bankprocesslist/hooks/useBankProcessListPage.js
Count-frontend/src/pages/transaction/lib/transactionApi.js          # Meta/Search/History/Submit(PAYMENT→Spring)
Count-frontend/src/pages/transaction/lib/transactionSubmitNormalize.js  # PAYMENT submit 请求/响应适配
Count-frontend/src/pages/transaction/lib/transactionPaymentLogic.js # 仍消费 search 同形 left/right_table
Count-frontend/src/pages/account/accountListApi.js                  # Transaction Meta 复用账户 list
Count-frontend/src/utils/api/currencyApi.js                         # Transaction Meta 复用币种 list
backend/src/main/resources/sql/migrate_upline_role_to_supplier.sql  # UPLINE → SUPPLIER 一次性迁移
```
