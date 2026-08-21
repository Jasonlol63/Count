# 前端 Spring Boot 迁移记录

> **用途**：记录 `Count-frontend/` 在 PHP → Spring Boot 迁移过程中**已改动的部分**，供后续开发 / AI 会话快速对齐上下文。  
> **前端仓库路径**：`../Count-frontend/`（与本 `Count/` 后端仓库并列）。
>
> **本文件是 `docs/` 目录下唯一需要查阅的文档。** 原本分散在 16 个独立 `.md` 文件里的内容（Maintenance 各页面、Process/Bank Process List、Account 多公司归属、Transaction Rate/金额精度/Description 规则、Customer/Domain Report、Accounting Due、Data Capture、登录与后端权限总览等）已**全部合并**为第 18–33 节，原文件仅保留一行跳转说明、不再单独维护。找资料直接在本文件搜索关键字，或查文首目录。

**最后更新**：2026-08-19（第 1–17 节：Process List / Bank Process List 列表加载实为 PHP + 死代码 bug，纠正第 2 节旧「已迁移」标注，见第 17 节；Data Capture `4f00f14` 整批回退修复见 [第 32 节 §0](#32-data-capture--spring-api-对齐说明)；第 7 节「尚未迁移」清单同步更新。**本次额外把 `docs/` 目录下其余 16 个文件全部合并进来，成为第 18–33 节，不再分散维护。**

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
15. [Login / Auth 前端回归修复（2026-08-14）](#15-login--auth-前端回归修复2026-08-14)
16. [Account 页面 Network Tab PHP 残留排查（2026-08-14）](#16-account-页面-network-tab-php-残留排查2026-08-14)
17. [Process List / Bank Process List 列表加载仍是 PHP + 死代码 bug（2026-08-19）](#17-process-list--bank-process-list-列表加载仍是-php--死代码-bug2026-08-19)
18. [Maintenance 侧边栏导航（Spring SPA）](#18-maintenance-侧边栏导航spring-spa)
19. [Payment Maintenance — List / Delete（Spring）](#19-payment-maintenance--list--deletespring)
20. [Bank Process Maintenance — List / Delete（Spring）](#20-bank-process-maintenance--list--deletespring)
21. [Bank Process Status 编辑锁定规则](#21-bank-process-status-编辑锁定规则)
22. [Games Process List — Spring API 对齐说明](#22-games-process-list--spring-api-对齐说明)
23. [Account 多公司归属 (Account ↔ Company Multi-Tenant)](#23-account-多公司归属-account--company-multi-tenant)
24. [Transaction list filters — Show Payment / Show Win/Loss / Show all 0 balance](#24-transaction-list-filters--show-payment--show-winloss--show-all-0-balance)
25. [Transaction Payment / Payment History — Data Capture Win/Loss 补聚合](#25-transaction-payment--payment-history--data-capture-winloss-补聚合)
26. [RATE Middle-Man / Rate-Mul / Platform Fee（Spring Boot 现行实现）— Transaction 专项](#26-rate-middle-man--rate-mul--platform-feespring-boot-现行实现)
27. [Transaction Amount Precision — 金额精度专项](#27-transaction-amount-precision)
28. [Transaction Description Storage — 描述入库规则](#28-transaction-description-storage)
29. [Customer Report — Spring API 迁移说明](#29-customer-report--spring-api-迁移说明)
30. [Domain Report — Spring API 迁移说明](#30-domain-report--spring-api-迁移说明)
31. [Accounting Due Frequency 业务规则](#31-accounting-due-frequency-业务规则)
32. [Data Capture — Spring API 对齐说明](#32-data-capture--spring-api-对齐说明)
33. [Login → Permission → 各业务页面功能说明（后端总览）](#33-login--permission--各业务页面功能说明)

> **索引提示**：本文件现已是 `docs/` 目录下**唯一**的技术文档，原本分散的 16 个独立 `.md` 文件内容全部合并进第 18–33 节（保留原文件，改为跳转说明，不再单独维护）。找东西直接在本文件内搜索关键字即可，不用再猜要开哪个文件。

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
| **Auth / Session** | ⚠️ 部分（2026-08-14 重新核实） | `/auth/*` | Login / Secondary Password 页 **直调** `authApi.js`（已于 2026-08-14 修复回归，见第 15 节）；`AuthenticatedLayout.jsx` / `companySessionSync.js` / `resetPassword.js` 目前**仍是 PHP**，待修（§15.4） |
| **Tenant 列表** | ✅ 已迁移 | `GET /auth/tenant-accessible` | `tenantAccessibleApi.js` |
| **Domain** | ✅ 已迁移 | `/api/domain/*` | `domainApi.js` + `domainHelpers.js` |
| **Admin (User List)** | ✅ 已迁移 | `/api/userlist/*` | `userListApi.js` |
| **Account (Member)** | ✅ 全量已迁移 | `/api/account/*` + `/api/currency/*` | `accountListApi.js` + `currencyApi.js`；`AccountListPage` 直调 Spring |
| **Currency** | ✅ 已迁移 | `/api/currency/*` | `currencyApi.js` |
| **Announcement / Maintenance** | ✅ 已迁移 | `/api/announcement/*` | `apiUrl.js` 重写（页面仍写 PHP 路径） |
| **Auto Renew** | ⚠️ 部分 | `/api/auto-renew/*` + Domain Comm | 列表 / reject / **approve** 直调 Spring；Comm 用 `domain/list` + `update-setting` |
| **Ownership** | ✅ API 已迁移 + **数据层已对齐 Spring** | `/api/ownership/*` | `apiUrl.js` 重写 + `ownershipRowHelpers` normalize |
| **Process** | ⚠️ 写操作已 Spring，**列表加载实测仍是 PHP**（2026-08-19 发现，见第 17 节） | `/api/process/*` + `/api/currency/list` | Add/Update/Status/Delete/description CRUD 已直调 Spring；但页面真正的列表数据源 `processRoutePrefetch.js` / `ProcessListPage.jsx` 的 `fetchRows` 仍在打 `processlist_api.php` 等 PHP 端点，且 `processListApi.js` 依赖的 4 个 `processListHelpers.js` 导出函数根本不存在（死代码），PHP 端点下线后整页很可能 500。旧版本节曾标「✅ Games List 已迁移」是**不准确的** |
| **Bank Process** | ⚠️ 部分，**列表加载与 Process 同源同 bug**（见第 17 节） | `/api/bank-process/*`、`/api/bank-country-option/*`、`/api/account/*` | Add/Update/Status/Delete/Remark/Resend/Accounting Due Post-to-Transaction 已 Spring（见第 10 节）；但 `useBankProcessListPage.js` 的列表加载同样经由 `processRoutePrefetch.js`，与 Process List 共享第 17 节的 PHP 依赖问题 |
| **Transaction / Report / Data Capture / Member** | ⚠️ 部分 | `/api/transaction/search` + `/history` + `/submit` + Meta（account/list + currency/list）；**Data Capture Games form / 币别 / description / tenant picker / Formula CRUD / Bank draft / Summary submit（Games·Bank 公司范围）** 已 Spring | **Transaction Payment 页** Meta / Search / History / Submit（含 RATE）已 Spring；Contra Inbox 无 pending（Submit 即时 APPROVED）；SSE ticket 未接 Spring。**Report** 完全未迁移（`api/reports/*`）。**Data Capture**：2026-08-14 commit `4f00f14` 曾把 Games/Formula/Bank draft/Summary submit 整批回退成 PHP，2026-08-19 已修复回 Spring（见 [`datacapture-spring-api.md` §0](./datacapture-spring-api.md#0-2026-08-19修复-commit-4f00f14-造成的整批回退)）；**仅真 AP/IG group ledger**（非 C168/Bank 公司范围）的 process id 解析、群组币别聚合、Summary submit、草稿表格仍走 PHP（见该文档第 4 节）。**Member Win/Loss**（`api/member/*`）完全未迁移 |

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
- `tenant_has_game` / `tenant_has_bank`（**勿用**旧 PHP `company_has_bank` / `company_has_gambling`；读 `sessionTenant.js`）
- `is_current_tenant_c168`
- `permissions[]`（小写，见 `sidebarPermissions.js`）

Maintenance 侧边栏与 Bank Process 入口规则：[`maintenance-navigation.md`](./maintenance-navigation.md)

### 3.4 `utils/company/loginScope.js` / `sharedCompanyFilter.js`

- Group / Company 登录形态、`group_id` 筛选、C168 Domain/AutoRenew 入口
- 与 Spring `tenant_type`、`parent_tenant_code` 对齐（非旧 `company` 表字段）

---

## 4. 各模块已改文件

### 4.1 Auth / Login

> **2026-08-14 状态核实**：本表原先整体标为已迁移，但实测仓库一度整体回归 PHP 约定。下表按**当前实际状态**列出，✅=本次已修复/确认走 Spring，❌=仍是 PHP（详见 [§15.4](#154-已知仍未修复本次明确不处理超出仅-login-范围)）。

| 文件 | 状态 | 改动 |
|------|------|------|
| `pages/login/LoginPage.jsx` | ✅ | `authApi.loginWithTenant` / `fetchCurrentUser`；维护公告改 `GET /api/announcement/getMaintenanceInLogin` |
| `pages/login/SecondaryPasswordPage.jsx` | ✅ | `verifyOwner/UserSecondaryPassword` + `logoutSession` + `fetchCurrentUser` |
| `utils/auth/authApi.js` | ✅ | 统一 Spring `/auth/*`（login / current-user / logout / switch-tenant / secondary / reset）——本身未回归，只是未被上两个页面调用 |
| `pages/login/resetPassword.js` | ❌ | 仍调 `api/users/send_reset_tac_api.php` / `api/users/reset_password_api.php`；`authApi.js` 已有 `sendResetTacRequest` / `resetPasswordRequest` 待接线 |
| `components/AuthenticatedLayout.jsx` | ❌ | 仍调 `api/session/current_user_api.php`（×3）/ `api/session/logout_api.php` / `api/announcements/announcement_get_dashboard_api.php` |
| `utils/company/companySessionSync.js` | ❌ | 仍调 `api/session/update_company_session_api.php`，应改 `authApi.switchSessionTenant()` |

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

**2026-08-21 复核**：本节描述的契约此前曾被一次批量回退（`4f00f14`，与 Data Capture 那次同批事故）打回
PHP——`domainApi.js`/`domainHelpers.js` 虽然内容和本节一致，但实际页面代码（`DomainPage.jsx` 及全部
子弹窗）当时完全没有 import 它们，仍在打 `domain_api.php`，`domainHelpers.js` 里本节提到的
`groupToTenantSaveEntry` 等几个函数当时也确实不存在。已于本次重新按本节契约把前端接线补回、并把
`domainHelpers.js` 缺失的桥接函数重新实现，详细改动清单见 `Count-frontend/docs/domain-springboot-rewire.md`。

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
| Add | `POST /api/account/add` | body `UserListDTO` camelCase；`currencyIds[]` 一并写入；**`account_id` 仅在同一 `scopeTenantId` 内唯一**（不同公司可同名） |
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
| `process.status` | `status`（`ACTIVE` / `INACTIVE`；UI 显示 Active / Inactive） |
| `currencyCode`（DTO 顶层） | `currency` |
| `processDays[].dayOfWeek`（1–7） | 拼成 `day_use`（如 `MON,THU`） |
| `process.id` | `id` |
| `process.currencyId` | `currency_id` |
| `process.createdBy` / `updatedBy`（String = `login_id`） | `created_by` / `updated_by`（Edit 直接展示） |

展示字符串 **只在前端** `formatProcessDescriptionLabel` / `formatProcessDayUseLabel` 生成；API 保持结构化 list。

---

## 7. 尚未迁移 / 仍走 PHP

以下模块**未**在 `apiUrl.js` 中做 Spring 重写，或仅部分 endpoint 迁移（2026-08-19 核实更新）：

- **Process List / Bank Process List 列表加载**（**优先级最高，见第 17 节**）：写操作（add/update/status/delete/description CRUD）已 Spring，但页面真正展示用的列表数据源 `processRoutePrefetch.js` 仍打 PHP 且 `processListApi.js` 有死代码 bug（引用的 4 个 helper 函数不存在），两个页面实测很可能整页 500；旧版本节曾错误标注「已迁移」
- **Transaction Payment 页**：Meta / Search / History / Submit 已直调 Spring（见第 11 节）；**Maintenance transaction** 仍 PHP
- **Report**：`api/reports/*`，完全未迁移，无 Spring 端点规划
- **Data Capture / Summary**：Games 表单 + Formula CRUD（save/update/delete + Account + Currency + Source 行内）+ Bank draft + Summary submit（Games/Bank 公司范围）已 Spring，2026-08-14 曾被 `4f00f14` 整批回退、2026-08-19 已修复（见 [`datacapture-spring-api.md` §0](./datacapture-spring-api.md#0-2026-08-19修复-commit-4f00f14-造成的整批回退)）；**仅真 AP/IG group ledger**（process id 解析 `get_group_process_id`、群组币别聚合、Submit、草稿表格）仍走 PHP
- **Bank Process List**：写操作（Add/Update/Status/Delete/Remark/Resend/Accounting Due Post-to-Transaction）已 Spring（见第 10 节）；**列表加载**与 Process List 共享第 17 节的 PHP 依赖问题
- **Member Win/Loss**：`api/member/*`（账户 meta 可复用 `/api/account/list`）
- **Maintenance 业务页**（formula/transaction/payment 等）：仍 PHP
- **User Access 部分接口**
- **Auth 残留 PHP / 字段不匹配**（见 [§15.4](#154-已知仍未修复本次明确不处理超出仅-login-范围)）：`resetPassword.js`、`companySessionSync.js` 仍调 PHP；`sidebarPermissions.js` / `loginScope.js` 仍读旧字段名（`company_has_gambling`/`company_has_bank`/`is_current_company_c168`），与 Spring 实际返回的 `tenant_has_game`/`tenant_has_bank`/`is_current_tenant_c168` 不匹配，可能影响登入后默认落地页路由判断

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

### 9.7 已知缺口（2026-07-27 更新）

| 项 | 说明 |
|----|------|
| Add form meta | ✅ `fetchProcessFormMeta`（Spring currency + description + 本地 weekday）；`existingProcesses` 来自列表行 |
| List process 服务端 search / showInactive | 暂无；✅ 客户端 `applyProcessListFilters`（`fetchGamesProcessListSlice`） |
| 前端 add/update/status/delete / description | ✅ 全 Spring（见 [`process-list-spring-api.md`](./process-list-spring-api.md)） |
| Edit 打开 | ✅ list 行本地回填（无 get API） |
| Copy From | ✅ 列表行本地 patch（无 PHP `copy_from`） |
| PHP `addprocess_api` / `processlist_api` | ✅ Games Process List 页已移除 |

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
> 5. **手动 PROFIT Submit**（→ **Win/Loss**；From + To；正数 amount；From + / To −）  
> 6. **手动 RATE Submit**（→ transfer **Cr/Dr** + 可选 Middle-Man fee **Win/Loss**；两腿 + `transactions_rate`；Middle-Man 账户+rate 成对）  
> **不含**：Type Search 过滤开关、**Contra Inbox 审批**、其他 type Submit。  
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
| **手动 PROFIT Submit** | `submit_api.php` | ✅ `POST /api/transaction/submit` | From + To；正数 amount；Win/Loss（From + / To −） |
| **手动 RATE Submit** | `submit_api.php` | ✅ `POST /api/transaction/submit` | 两腿 Cr/Dr + 可选 Middle-Man Win/Loss + `transactions_rate`；账户+rate 成对 |
| Contra Inbox / 审批 | `contra_*` | — | Submit 即时 `APPROVED`；Inbox 前端返回空列表（无 PHP） |

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

- Type Search / Capture-only / show 0 balance 等 **v1 未实现独立 API**；Search 固定走 Spring `POST /api/transaction/search`（Type Search 用同期 Search 再前端筛有动账行）。

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
- 数据源：**BP** `WIN`/`LOSE` + **手动 ADJUSTMENT** + **手动 PROFIT** + **Domain / 手动转账** `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`（`bank_process_posted_id IS NULL`），均 `APPROVED`

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

**手动 PROFIT History 展示**

| 当前查看账户 | Win/Loss | Description | Id Product |
|-------------|---------|-------------|------------|
| 收款方（From） | **+amount** | `PROFIT TO {付款方}` | `PROFIT` |
| 付款方（To） | **−amount** | `PROFIT FROM {收款方}` | `PROFIT` |

- 入库：`description` 空；History 派生（同 transfer 规则）；`Cr/Dr = 0.00`
- Search 合并进 BP Win/Loss（`aggregateManualProfitWinLoss`：From + / To −）

**手动 RATE History 展示**

| 当前查看账户 | Description | Id Product | 列 |
|-------------|-------------|------------|----|
| 收款方（From）transfer | `EXCH RATE {rate} {ccy1} {amt} > {ccy2} \| TO {付款方}` | `RATE` | Cr/Dr |
| 付款方（To）transfer | `EXCH RATE {rate} {ccy1} {amt} > {ccy2} \| FROM {收款方}` | `RATE` | Cr/Dr |
| Middle-Man Rate Multiplier（仅 middleman 可见） | `MARKUP {rate} {ccy1} {amt} > {ccy2} \| FROM {leg1 To}` | `RATE` | Win/Loss（第二币） |
| Middle-Man Fee（仅 middleman 可见） | `MARKUP X {ccy1} {amt} > {ccy2} \| FROM {leg1 To}` | `RATE` | Win/Loss（第二币） |
| leg2 付款方（Middle-Man To 侧） | **不展示** | — | — |

例：`EXCH RATE 1.7 MYR 1000 > CNY | TO TEST1` / `EXCH RATE 1.7 MYR 1000 > CNY | FROM TEST5`  
- `{rate}` 优先 `rate_expression`（如 `1.7` / `/1.7`），否则 `exchange_rate`  
- `{ccy1} > {ccy2}` = leg1→leg2（先 CNY 则 `CNY > MYR`）  
- 两腿共用同一 FX 前缀；`| TO/FROM` 按**该腿** From/To 相对当前账户（同 PAYMENT）  
- Middle-Man：第二币 Win/Loss；Rate Multiplier 与 Fee **各一行**（可只填其一或都填）；Fee 为第一币输入 × 主汇率换算；`FROM` = 第一个 To Account

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

**v1 不做（History）**：`pure_type_search`；Member PDF 描述规则（`member_view`）仍用 Spring history 原字段。

### 11.7 手动 PAYMENT / CLAIM / CLEAR / CONTRA / ADJUSTMENT / PROFIT Submit API（已实现 2026-07-22；PROFIT 2026-07-23）

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
| `transactionType` | 可省略，默认 `PAYMENT`；transfer：`PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`；或 `ADJUSTMENT` / `PROFIT` |
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

**PROFIT 请求体（From + To）**

```json
{
  "tenantId": 95,
  "transactionType": "PROFIT",
  "transactionDate": "23/07/2026",
  "toAccountId": 12,
  "fromAccountId": 8,
  "currencyCode": "MYR",
  "amount": 100.00,
  "remark": ""
}
```

- `fromAccountId` / `toAccountId` 必填且不同；`amount` **正数**
- 写入：单行 `PROFIT`；`description` 空；From = 收款方（Win/Loss **+**），To = 付款方（Win/Loss **−**）
- Search / History：**Win/Loss**（非 Cr/Dr）；Id Product=`PROFIT`；desc=`PROFIT FROM {收款方}` / `PROFIT TO {付款方}`
- DB：`transactions.transaction_type` ENUM 需含 `PROFIT` — 脚本 [`migrate_transaction_type_add_profit.sql`](../backend/src/main/resources/sql/migrate_transaction_type_add_profit.sql)

**RATE 请求体（两腿 + 汇率；可选 Middle-Man）**

```json
{
  "tenantId": 95,
  "transactionType": "RATE",
  "transactionDate": "23/07/2026",
  "leg1ToAccountId": 12,
  "leg1FromAccountId": 8,
  "leg1CurrencyCode": "MYR",
  "leg1Amount": 1000.00,
  "leg2ToAccountId": 8,
  "leg2FromAccountId": 12,
  "leg2CurrencyCode": "CNY",
  "leg2Amount": 2900.00,
  "exchangeRate": 3.0,
  "rateExpression": "3.0",
  "middlemanAccountId": 20,
  "middlemanRate": 0.1,
  "middlemanAmount": 10.00,
  "remark": ""
}
```

- 除法 UI `/1.7`：`rateExpression="/1.7"`，`exchangeRate` 传归一乘数 `1/1.7`
- **无 Middle-Man**：`leg2Amount ≈ leg1Amount × exchangeRate`（容差 0.02）；不传 middleman 字段
- **有 Middle-Man**：`middlemanAccountId` +（`middlemanRate` 和/或 `middlemanAmount`）  
  - `middlemanAmount` = **Fee 第一币输入**（例 10 MYR）；后端换算 `fee × exchangeRate` 成第二币  
  - Rate 份额 = `leg1Amount × middlemanRate`（第二币）  
  - 可只填 Rate、只填 Fee、或都填；account 且无 rate/fee → 拒绝；无 account 且无 rate/fee → 普通 RATE  
  - 写入：2 行 transfer Cr/Dr + Rate 份额（To−/From+）+ Fee 份额（**仅 middleman +WL**，不对 leg2 付款方记 −WL）+ `transactions_rate`；`leg2Amount ≈ gross − (rate份额 + fee份额)`  
  - History：Rate → `MARKUP {rate} …`；Fee → `MARKUP X …`（仅 middleman）；有 Fee 时 leg1 remark = `CHARGE …`- History desc（Middle-Man）：见上；leg2 付款方 fee/rate 行不展示
- DB：[`migrate_rate_tables_optimized.sql`](../backend/src/main/resources/sql/migrate_rate_tables_optimized.sql)

- `transactionApi.js`：`submitTransaction` — `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`/`ADJUSTMENT`/`PROFIT`/`RATE` → Spring JSON（不再回退 PHP）
- `transactionSubmitNormalize.js`：`buildSpringSubmitRequest` / `normalizeSpringSubmitResponse`（含 RATE `leg1*`/`leg2*`/`exchangeRate`/`rateExpression`/`middleman*`）
- `useTransactionForm.js`：RATE Middle-Man 支持 Fee 和/或 Rate Multiplier

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
| `transactionApi.js` | Meta → Spring account/currency/list + localStorage 币种排序；Search → `/search`；History → `/history`；**全部手动 Submit 含 RATE → `/submit`**；Contra Inbox 空列表；不再请求任何 `api/transactions/*.php` |
| `subscribeAppRealtime.js` | 不再打 `ticket_api.php`（Spring 无 SSE ticket） |
| `paymentHistoryMemberReportExport.js` | 币种 → `/api/currency/available`；明细 → `/api/transaction/history` |
| `transactionSubmitNormalize.js` | **新建** — Submit request/response 适配 |
| `transactionHistoryNormalize.js` | Spring history → BF + 明细行 |
| `transactionSearchNormalize.js` | Spring `rows` → `left_table` / `right_table`（balance 正负分列） |
| `transactionAccountHelpers.js` | 账户 Meta normalize + Category priority |
| `useTransactionSearch.js` | v1 跳过 zero-balance / payment / capture 展示过滤，直接渲染 API 行 |

### 11.5 本期明确不做

- Data Capture 行合并进 Search
- RATE legacy `transaction_entry` / `transactions_rate_details`（已用优化表）
- RATE Middle-Man 的 Service Fees remark 展示（History MARKUP 已支持）
- **Contra Inbox** 审批（Spring CONTRA 即时 APPROVED，无 pending 队列）
- `type_account_search` / `type_transaction_search` 独立 API（Type Search 改走 period `/search`）
- SSE / realtime ticket
- `user_currency_order_api` 服务端持久化（localStorage）

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

## 15. Login / Auth 前端回归修复（2026-08-14）

### 15.1 背景

本次会话开始时 `Count-frontend` 的 `git status` 显示 `vite.config.js`、`.env.example`、`LoginPage.jsx`、`SecondaryPasswordPage.jsx` 等一批文件相对最近一次提交（`d7f8207 feat: migrate auth and bank process to Spring Boot...`）**倒退回了 PHP 约定**（`VITE_PHP_PROXY_TARGET`、`/api/session/login_api.php` 等），导致前端登录走不通 Spring。用户要求**先只修 Login 功能**，其余页面暂不处理。

**未改动后端**：`AuthController` / `SessionUser` / `GlobalExceptionHandler` 等 Spring 代码本次**完全未修改**。租户驱动模型（登录只认 `tenant_code` + `login_role` + `login_id`/`account_id`，不接受 `scope`/`company_id`/`group_id`/`group_only`）在后端本来就是既有实现（`Count/backend` 是纯 Spring Boot 项目，仓库内**没有任何 PHP 服务**）——本节只是把前端重新接回这套已存在的后端契约。

### 15.2 Dev Proxy 恢复指向 Spring 8082

| 文件 | 修复前（倒退状态） | 修复后 |
|------|--------------------|--------|
| `Count-frontend/.env.example` | `VITE_PHP_PROXY_TARGET=http://127.0.0.1:8000` | `VITE_SPRING_PROXY_TARGET=http://127.0.0.1:8082` |
| `Count-frontend/vite.config.js` | `server.proxy` 把 `/dashboard.php` `/member.php` `/owner_secondary_password.php` `/api` `/reset-password.php` `/images` `/js` 全部指到不存在的 PHP target | 只保留 `/auth` 与 `/api` → `springTarget`（默认 `http://127.0.0.1:8082`），与 `d7f8207` 提交时一致 |

本地未纳入版本控制的 `Count-frontend/.env` 其实一直是对的（`VITE_SPRING_PROXY_TARGET=8082`）——只有 `.env.example` 的示例值和 `vite.config.js` 的 fallback 默认值、代理表倒退了。

### 15.3 Login 页面改回直调 Spring `/auth/*`

`utils/auth/authApi.js` 本身没有倒退，是完整可用的 Spring 封装；问题在于 `LoginPage.jsx` / `SecondaryPasswordPage.jsx` 没有调用它，而是手写了指向 `.php` 文件的 `fetch`。本次把两个页面重新接回 `authApi.js`：

| 文件 | 改动 |
|------|------|
| `pages/login/LoginPage.jsx` | 会话 bootstrap → `fetchCurrentUser()`；维护公告 banner → `GET /api/announcement/getMaintenanceInLogin`（原 `api/maintenance/get_public_api.php`，`AnnouncementController` 已有此 endpoint）；**删除** `company_id` 输入时的 debounce 静默校验请求（原 `api/company/verify_api.php` 无 Spring 对应端点；该请求本来就是 best-effort 且注释写明「silent; login validates」，登录本身会校验 tenant_code）；提交 → `loginWithTenant()` → `POST /auth/login` |
| `pages/login/SecondaryPasswordPage.jsx` | 改用 `fetchCurrentUser()` / `verifyOwnerSecondaryPassword()` / `verifyUserSecondaryPassword()` / `logoutSession()` |

**登录提交请求字段对照**

| 旧 PHP FormData | Spring `POST /auth/login` |
|------------------|---------------------------|
| `action=login` | 无需此字段（Spring 用 HTTP method + path 区分动作） |
| `company_id` | `tenant_code` |
| `login_role` / `login_id` / `account_id` / `remember_me` | 字段名不变 |
| （PHP 侧从未真正使用，前端也未传） | 后端**不接受** `scope` / `group_id` / `group_only`；身份与租户完全由 `tenant_code` + `login_role`（+ `login_id` 或 `account_id`）解出 |

**登录成功响应字段对照**

| 旧 PHP 响应字段 | Spring `POST /auth/login` 响应 | 前端取值方式 |
|-------------------|-------------------------------|--------------------|
| `status` / `redirect` / `user_type` | 字段名不变 | 不变 |
| `login_scope`（`"group"` \| `"company"`） | `tenant.type`（`"GROUP"` \| `"COMPANY"`） | `String(tenant.type).toLowerCase()` |
| `login_identifier` | `tenant.code` | 直接取 `tenant.code` |
| `company_id`（数字） | `tenant.id`（仅 `tenant.type === "COMPANY"` 时对应旧语义） | `Number(tenant.id)` |
| （无） | 另有 `login_tenant`（用户实际输入登录的租户对象，Owner 跨租户登录时可能与 `tenant` 不同）、`tenant.parent_id` / `tenant.parent_code` | 前端暂未消费，预留 |

失败响应统一为 `GlobalExceptionHandler` 产出的 `{ "status": "error", "message": "..." }`（HTTP 200），前端读 `data.message` 的逻辑不变。

`seedDashboardFilterFromLogin()`（`utils/company/sharedCompanyFilter.js`）**本身未改动**——它仍然接收 `loginScope` / `loginIdentifier` / `sessionCompanyId` / `sessionCompanyCode` 这组参数名；`LoginPage.jsx` 现在从 Spring 返回的 `tenant` 对象派生出这些值再传入，而不是直接透传后端字段（因为后端已不再直接返回 `login_scope` / `company_id` 这些字段名）。

### 15.4 已知仍未修复（本次明确不处理，超出「仅 Login」范围）

以下文件同样在本次会话开始时相对 Spring 迁移状态回归了 PHP，且旧版 §4.1 曾错误记录为「已迁移」——**实测当前仍在调用 PHP 端点**，留给下一次任务处理，本次未动：

| 文件 | 仍在调用的 PHP 端点 | 建议对应的 Spring 调用 |
|------|----------------------|---------------------------|
| `pages/login/resetPassword.js` | `api/users/send_reset_tac_api.php`、`api/users/reset_password_api.php` | `authApi.sendResetTacRequest()` / `authApi.resetPasswordRequest()`（已封装 `POST /auth/send-reset-tac`、`POST /auth/reset-password`，未接线） |
| `components/AuthenticatedLayout.jsx` | ~~`api/session/current_user_api.php`（3 处）、`api/session/logout_api.php`~~ ✅ **已确认修复**（2026-08-14 第 16 节复核：`4f00f14` 提交已把这三处改回 `authApi.fetchCurrentUser()` / `authApi.logoutSession()`，当前 `AuthenticatedLayout.jsx` 内已无 `current_user_api.php` / `logout_api.php`）；`api/announcements/announcement_get_dashboard_api.php`（通知铃铛，仅点击时触发）经 `apiUrl.js` 重写表已可用，见第 16 节 | 通知铃铛 → `GET /api/announcement/getDashboardAnnouncements`（`AnnouncementController`，经 `apiUrl.js` 重写） |
| `utils/company/companySessionSync.js` | `api/session/update_company_session_api.php` | `authApi.switchSessionTenant()` → `POST /auth/switch-tenant?tenant_id=` |
| `utils/auth/sidebarPermissions.js` / `utils/company/loginScope.js` | 未直接调用 PHP API，但仍按旧字段名读 `SessionUser`：`company_has_gambling` / `company_has_bank` / `company_code` / `is_current_company_c168`，与 Spring 实际返回的 `tenant_has_game` / `tenant_has_bank` / `tenant_code` / `is_current_tenant_c168` **不匹配** | 需要逐个字段核对改名；影响面包括 `resolveDefaultLandingPath()` 的 Bank-only Process 路由分支和 datacapture 可见性判断，可能选错登录后落地页。范围明显大于「仅 Login」，建议单独立项处理，**本次未动** |

### 15.5 验证

- `npx vite build`：编译通过，无报错，`dist/` 未纳入版本控制。
- 未在本次会话内做端到端人工验证（需要本地起 Spring Boot `8082` 服务）；建议下次验证：Admin / Member 两种角色登录，确认 `POST /auth/login` 请求体只含 `tenant_code`/`password`/`login_role`/`login_id` 或 `account_id`/`remember_me`；Owner 首次登录若需二级密码，能正确跳转 `/owner-secondary-password` 并验证成功后落地到正确页面。

---

## 16. Account 页面 Network Tab PHP 残留排查（2026-08-14）

### 16.1 背景

用户反馈：停留在 Account List 页面时，DevTools Network tab 出现一批 500 的 PHP 请求（`dashboard_bootstrap_api.php`、`processlist_api.php?permission=Bank/Games`、`get_company_currencies_api.php`、`user_currency_order_api.php`），要求「account 页面所有 API 都必须是 Spring Boot 格式，不再出现 PHP」。

排查结论：**Account 页面自身的 API 调用本来就是纯 Spring**（`POST /api/account/list?tenant_id=`、`GET auth/current-user`、`GET auth/switch-tenant`，均 200）。PHP 请求另有两个来源，均**不在** Account 模块代码内：

1. **`AuthenticatedLayout.jsx` 的页面无关 idle-warm 逻辑**：只要 `me` 加载完成，就会在每个已登录页面（不只是 Account）后台预热 Dashboard 与 Process List 的路由缓存：
   - `warmDashboardRouteCache()`（来自 `pages/dashboard/dashboardRoutePrefetch.js`）→ 打 `dashboard_bootstrap_api.php`；Dashboard 模块整体从未迁移到 Spring，无对应端点。
   - `warmProcessListRouteCache()` / `warmBankProcessListRouteCache()`（来自 `pages/processlist/processRoutePrefetch.js`）→ 打 `processlist_api.php` + `get_company_currencies_api.php` + `user_currency_order_api.php`；且**停留在 Account List / Add Account 页面时会额外被 eager 触发一次**（`pathnameIs("account-list", path) || pathnameIs("add-account", path)` 分支），这是网络面板里这批请求反复出现的直接原因。
2. **`apiUrl.js` 的 PHP→Spring 重写表在当天 `4f00f147`（"...already change account/admin/transaction page to springboot api"）提交中被整体删空**，`buildApiUrl()` 退化成纯直通（`new URL(pathAndQuery, base).href`）。该重写表原本覆盖 Ownership / Announcement / Maintenance / Auto-Renew 等模块的只读接口（把旧 PHP 路径映射到已存在的 Spring 端点），删除后这些模块即使代码没变也会直接命中已下线的 PHP 路径并 500——**与 Account 页面无关，但是当天引入的明显回归**，一并处理。

### 16.2 深挖后发现的额外风险（改变了原定修复方案）

原计划是把 `processRoutePrefetch.js` 里的 `processlist_api.php` / `get_company_currencies_api.php` 换成看似已存在的 Spring 调用（`processListApi.fetchProcessListByTenantId` + `currencyApi.fetchCurrencyListByTenantId`）。深入代码后发现这条路径**不能直接切换**，原因：

- `pages/processlist/processListApi.js` 顶部 `import { normalizeProcessListRows, normalizeProcessStatusKey, PROCESS_WEEKDAY_OPTIONS, resolveProcessListActiveTenantId } from "./processListHelpers.js"` —— **这四个 named export 在 `processListHelpers.js` 里根本不存在**（全仓库 grep 只有 `processListApi.js` 自己引用这些名字）。`fetchProcessListByTenantId()` 因此是**死代码 + 运行期必炸**（调用即 `TypeError: normalizeProcessListRows is not a function`），此前从未被任何页面实际调用过，所以这个 bug 一直没暴露。
- `ProcessListPage.jsx` 真正的「实时」列表加载（`fetchRows` → `fetchGamesProcessListSlice`）以及 `useBankProcessListPage.js` 的列表加载（`prefetchBankProcessListPayload` / `resolveBankProcessListRouteCache`），**其实都是走 `processRoutePrefetch.js` 里那几个 PHP 调用**——也就是说 Process List / Bank Process List 两个页面本身（不只是 Account 页触发的预热）目前也在因为 PHP 后端下线而整页 500，这是比 Account 页 network tab 更大范围的既有故障，且旧文档 §2/§9/§10 把它们标成「已迁移」是不准确的。
- Spring `POST /api/process/process-list` 不支持服务端 `search` / `showActive` / `showInactive` / `showAll` 过滤（§9.7 已知缺口），而 `ProcessListPage.jsx` 目前**没有**对应的客户端过滤兜底——贸然把数据源换成 Spring 会让搜索框和 Show Active/Inactive/All 开关直接失效，是比「network tab 有 500」更糟的功能回归。

结论：**Process List / Bank Process List 的 Spring 化需要单独立项**（补 `processListHelpers.js` 缺失的 normalize 函数 + 客户端过滤逻辑 + 回归测试两个页面），不适合在本次「清理 Account 页面 network tab」任务里顺手改掉。

### 16.3 本次实际改动

| 文件 | 改动 | 原因 |
|------|------|------|
| `Count-frontend/src/utils/core/apiUrl.js` | 从 `d7f8207` 提交恢复完整的 `buildApiUrl()` PHP→Spring 重写表（`get_owner_companies_api.php` / `auto_renew_api.php` / ownership 系列 / announcements 系列 / maintenance 系列） | 修复 `4f00f14` 引入的回归；Ownership / Announcement / Maintenance / Auto-Renew 重新可用 |
| `Count-frontend/src/components/AuthenticatedLayout.jsx` | `warmDashboardRouteCache()` 调用整段移除（保留 `prefetchRouteModule(spaPath("dashboard"))` 纯 JS chunk 预取，无网络请求）；`runProcessListWarm` 改成空函数（`() => {}`），并加注释说明原因 | Dashboard 和 Process List 两个目标模块自身都还没有可用的 Spring 数据源，继续预热只会在无关页面（含 Account）打出必炸的 PHP 请求；改成 no-op 后停留在 Account 页面时不再产生这批 500 |

**未改动**（明确排除在本次范围外，留给后续任务）：

- `pages/processlist/processRoutePrefetch.js`（以及它依赖的 `processListApi.js` / `processListHelpers.js`）本身仍是 PHP + 有死代码 bug；用户主动导航进 Process List / Bank Process List 页面时仍会看到 PHP 500（这是既有故障，不是本次改动引入的）。
- `pages/dashboard/dashboardRoutePrefetch.js` / `useDashboardPage.js` 仍调用 `dashboard_bootstrap_api.php`；Dashboard 页面本身仍是纯 PHP，本次只是不再从其他页面背景预热它。

### 16.4 验证

- `npx esbuild` 对改动的两个文件做语法检查通过（未跑完整 `vite build`，因为仓库内当时还有另一批与本任务无关的未提交改动 `transaction/` 相关文件，避免混淆改动来源）。
- 未做浏览器端到端验证（需要本地起 Spring Boot 服务 + 重新登录复现 Account 页面 network tab）；建议下次打开 Account List 页面用 DevTools 确认：`dashboard_bootstrap_api.php` / `processlist_api.php` / `get_company_currencies_api.php` / `user_currency_order_api.php` 均不再出现，`list?tenant_id=` / `current-user` / `switch-tenant` 仍 200。

### 16.4.1 Account Link modal 报错：`fetchAccountListByTenantId is not defined`（2026-08-14）

用户点击 Account List 某行的「+」（Link Account）按钮，`openLink()`（`AccountListPage.jsx:2536`）内部调用 `fetchAccountListByTenantId(tenantId)`（`AccountListPage.jsx:2551`）取待选账户池，但该函数**从未被 import** 进这个文件——`accountListApi.js` 的 import 列表（原 79-101 行）漏了它，纯粹的漏 import，不是 Spring 迁移问题（`fetchAccountListByTenantId` 本身早就是 `POST /api/account/list?tenant_id=`，纯 Spring，`accountRoutePrefetch.js` 等文件一直在正常用）。

修复：在 `AccountListPage.jsx` 的 `accountListApi.js` import 列表里补上 `fetchAccountListByTenantId`。

### 16.5 后续建议（下一次任务）

1. 修 `processListHelpers.js` 缺失的 `normalizeProcessListRows` / `normalizeProcessStatusKey` / `PROCESS_WEEKDAY_OPTIONS` / `resolveProcessListActiveTenantId`，让 `processListApi.js` 真正可用；同时确认 `bankProcessListApi.fetchBankProcessListByTenantId()` 返回的行形状与 `ProcessTable` / Bank Process 表格实际渲染字段一致（目前未验证，`normalizeRows` 只是浅层补字段，不是真正的 Spring DTO → UI 行转换）。
2. 补 `ProcessListPage.jsx` 的客户端 search / showActive / showInactive / showAll 过滤（Spring 不支持服务端过滤）。
3. 把 `processRoutePrefetch.js` 换成 Spring 调用后，再把 `AuthenticatedLayout.jsx` 里的 `runProcessListWarm` 恢复成真正预热（现在是空函数）。
4. Dashboard 模块目前在仓库和文档里都找不到任何 Spring 端点规划——需要先决定 `dashboard_bootstrap_api.php` 对应的 Spring 契约，再迁移页面本身和 `warmDashboardRouteCache`。

---

## 17. Process List / Bank Process List 列表加载仍是 PHP + 死代码 bug（2026-08-19）

### 17.1 背景

排查 Account 页面 network tab PHP 残留时（第 16 节，2026-08-14）意外发现：第 2 节 / 第 9 节 / 第 10 节把 Process List、Bank Process List 标注为「已迁移」是**不准确的**——那些小节只覆盖了写操作（add/update/status/delete/description CRUD），两个页面**真正用来展示数据的列表加载路径**从未切过 Spring，本次（2026-08-19）核实后在此正式记录、更正前面章节的状态标注。

### 17.2 问题一：列表数据源仍是 PHP

- `pages/processlist/ProcessListPage.jsx` 的 `fetchRows` → `fetchGamesProcessListSlice`，以及 `pages/bankprocesslist/hooks/useBankProcessListPage.js` 的列表加载（`prefetchBankProcessListPayload` / `resolveBankProcessListRouteCache`），**实际都经由** `pages/processlist/processRoutePrefetch.js`。
- 该文件仍在调用 `processlist_api.php`、`get_company_currencies_api.php`、`user_currency_order_api.php` 等 PHP 端点。仓库内 PHP 后端已下线，这些请求会直接 500。
- `AuthenticatedLayout.jsx` 原本还会在所有已登录页面后台预热这条路径（`warmProcessListRouteCache()`），第 16 节已把预热改成空函数止血，但**用户主动打开 Process List / Bank Process List 页面时，该路径仍会被真实调用**，止血未覆盖这个场景。

### 17.3 问题二：`processListApi.js` 存在死代码 bug

`pages/processlist/processListApi.js` 顶部：

```javascript
import {
  normalizeProcessListRows,
  normalizeProcessStatusKey,
  PROCESS_WEEKDAY_OPTIONS,
  resolveProcessListActiveTenantId,
} from "./processListHelpers.js";
```

这四个 named export **在 `processListHelpers.js` 里根本不存在**（全仓库 grep 只有本文件引用这些名字）。因此 `fetchProcessListByTenantId()` 从未被安全调用过——一旦被执行会直接 `TypeError: normalizeProcessListRows is not a function`。此前未暴露是因为 `ProcessListPage.jsx` 实际走的是上面 17.2 提到的 `processRoutePrefetch.js` 路径，从未调用过这个函数。

### 17.4 问题三：Spring 列表接口无服务端过滤

`POST /api/process/process-list`（见 §9.7）不支持服务端 `search` / `showActive` / `showInactive` / `showAll`；`ProcessListPage.jsx` 目前**没有**对应的客户端过滤兜底。若直接把数据源换成 Spring 而不补前端过滤，会让搜索框和 Show Active/Inactive/All 开关失效——比「network tab 有 500」更糟的功能回归。

### 17.5 结论与后续任务（未在本次改动）

需要单独立项处理，范围：

1. 补 `processListHelpers.js` 缺失的 `normalizeProcessListRows` / `normalizeProcessStatusKey` / `PROCESS_WEEKDAY_OPTIONS` / `resolveProcessListActiveTenantId`，让 `processListApi.js` 真正可用。
2. 确认 `bankProcessListApi.fetchBankProcessListByTenantId()` 返回的行形状与表格实际渲染字段一致（`normalizeRows` 目前只是浅层补字段，非真正的 DTO → UI 行转换，未验证）。
3. 补 `ProcessListPage.jsx` 的客户端 search / showActive / showInactive / showAll 过滤。
4. 把 `processRoutePrefetch.js` 换成上述 Spring 调用后，再把 `AuthenticatedLayout.jsx` 里的 `runProcessListWarm`（第 16 节改成的空函数）恢复成真正预热。
5. 两个页面的端到端回归测试（列表展示、搜索、状态过滤、Edit 回填）。

**本次（2026-08-19）未做任何代码改动**，仅核实并记录状态；第 2 / 7 节的状态表已同步更正。

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

---

## 18. Maintenance 侧边栏导航（Spring SPA）

> 原始独立文件：`docs/maintenance-navigation.md`（内容已合并于此；原文件已改为跳转说明）


Maintenance 子菜单显示规则、Bank 公司入口、以及 **Spring `tenant_has_*` 与旧 PHP `company_has_*` 字段** 约定。修改 `AuthenticatedLayout`、sidebar 权限或 Maintenance 页面守卫时，**同步更新本文档**。

相关：

- Payment Maintenance 列表/软删：[`payment-maintenance-list-delete.md`](./payment-maintenance-list-delete.md)
- Session / 登录：`login-to-business-pages.md`、`frontend-springboot-migration.md` §3.3

---

### 1. Maintenance 子菜单路由

| 菜单文案（EN） | 路由 | pageKey |
|----------------|------|---------|
| Data Capture | `/capture-maintenance` | `capture-maintenance` |
| Transaction | `/transaction-maintenance` | `transaction-maintenance` |
| Payment | `/payment-maintenance` | `payment-maintenance` |
| Formula | `/formula-maintenance` | `formula-maintenance` |
| Bank Process | `/bankprocess-maintenance` | `bankprocess-maintenance` |

实现：`Count-frontend/src/components/AuthenticatedLayout.jsx`（Maintenance flyout submenu）。

---

### 2. 谁能看到 Maintenance 父菜单

| 函数 | 含义 |
|------|------|
| `showMaintenanceInSidebar(me)` | Owner / 全权限 / 有 `maintenance` 权限 / **limited maintenance** |
| `canAccessFullMaintenance(me)` | Owner、空 permissions、或含 `maintenance` |
| `canAccessLimitedMaintenance(me)` | 非 Owner、无 `maintenance` 权限，但当前 tenant 有 Game 或 Bank |

Limited 用户仍可见 **Transaction + Formula**（及 Bank 场景下的 Capture），但 **不含 Payment / Bank Process**（需 full maintenance）。

---

### 3. 各子入口显示条件

逻辑在 `AuthenticatedLayout.jsx`；下表为 2026-07-24 行为摘要。

| 子入口 | 显示条件 |
|--------|----------|
| **Data Capture** | `(fullMaintenance \|\| (limitedMaintenance && tenant_has_bank))` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Transaction** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Payment** | `fullMaintenance` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Formula** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Bank Process** | `fullMaintenance` **且** `shouldShowBankprocessMaintenanceInSidebar(me)` |

#### 3.1 Bank Process 专项：`shouldShowBankprocessMaintenanceInSidebar`

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

#### 3.2 典型故障（已修复 2026-07-24）

**现象**：Owner 登录 Bank 公司（如 BK），Maintenance 有 Payment / Formula，但 **没有 Bank Process**。

**原因**：Bank Process 侧边栏误读 `company_has_bank`；Payment 等项已用 `tenant_has_bank`。

**修复**：

- `shouldShowBankprocessMaintenanceInSidebar` → `sessionHasTenantBank(me)`
- `BankprocessMaintenancePage.jsx` 进入守卫 → `sessionHasTenantBank(user)`
- `useMaintenanceBankOnlyGuard.js` → `sessionHasTenantGame` / `sessionHasTenantBank`

---

### 4. Session 字段（Spring）

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

#### 4.1 勿再依赖的 PHP 字段

| 旧字段 | Spring 替代 |
|--------|-------------|
| `company_has_bank` | `tenant_has_bank` / `sessionHasTenantBank(me)` |
| `company_has_gambling` | `tenant_has_game` / `sessionHasTenantGame(me)` |
| `company_id`（session 活跃租户） | `tenant_id` |

---

### 5. Bank-only 公司路由守卫

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

### 6. Process 菜单 vs Bank Process Maintenance

| 概念 | 路由 | 说明 |
|------|------|------|
| **Bank Process List**（Process 权限） | `/bank-process-list` | 配置 BP、Accounting Due inbox |
| **Bank Process Maintenance** | `/bankprocess-maintenance` | 维护已入账 BP 交易行（软删等） |

Bank-only 登录时 Process 侧边栏指向 `bank-process-list`（非 `process-list`）。  
Bank Process **Maintenance** 仍在 Maintenance 子菜单下，需 **maintenance 权限 + tenant_has_bank**。

---

### 7. 关键文件索引

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

### 8. 变更检查清单

- [ ] 新增 Maintenance 子入口：是否更新 `AuthenticatedLayout` **与本文 §3**  
- [ ] 是否仍用 `tenant_has_*` / `sessionHasTenant*`，而非 `company_has_*`  
- [ ] Bank Process Maintenance 是否仍要求 **具体 Company**（非 Group-only）  
- [ ] Bank-only 公司是否仍走 `sidebarCompanySwitch` 允许路径  
- [ ] 修改 `SessionUser` 字段名时：同步 `sessionTenant.js` + 本文 §4  
- [ ] Maintenance 页是否 **不回归** Category pills（§9）

---

### 9. Category 筛选条（已移除）

2026-07-24 起，**所有 Maintenance 页面不再展示** 顶部 `Category:` pills（Games / Bank / Loan / Rate / Money）。

| 项 | 约定 |
|----|------|
| UI | 不渲染 `maintenance-permission-filter-header` |
| 仍走 PHP 的页 | Capture 仍在内部 **自动选择** category 传给旧 API；用户不可手动切换。Transaction（§10）和 Formula（§11）的 List/Update/Delete 均已切 Spring，但仍是内部自动选 category，不回归 UI 选择器 |
| Spring Payment / Bank Process Maintenance | 仅用 `tenantId`，本就不依赖 Category pills |
| 公司能力 | 由 Group/Company pill + session `tenant_has_*` 决定 sidebar 入口，不再重复 Category 行 |

涉及文件：`PaymentMaintenancePage`、`TransactionMaintenancePage`、`FormulaMaintenancePage`、`BankprocessMaintenanceFilters.jsx`。

---

### 10. Transaction Maintenance 数据契约（Spring，已切换）

只读列表（无 delete），把 `Count-frontend/pages/maintenance/transaction/*` 原本打的旧 PHP `api/transactions/maintenance_search_api.php` 换成 Spring 接口。**当前进度：后端（Mapper/Dao/DTO/Service/Controller）+ 前端均已实现并切换。**

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/transaction/*`（页面/表格/筛选组件不改，字段名已对齐） |
| 数据源 | `data_capture_line`（一行 = 一条明细，MAIN+SUB 全展示，不筛 `product_type`） |
| 租户 | **一律 `tenantId`**，与 Payment/Bank Process Maintenance 同一原则：不再传 / 校验 `company_id`、`group_id`、`view_group`、`report_scope`、`group_aggregate` 等 scope 参数（旧前端那套跨公司聚合是纯前端循环单租户请求实现的，后端从未支持过） |
| Category | **必填**，`dc.category = #{category}` 是 WHERE 里的硬条件（不是 `<if>` 可选项）。Games/Gambling/Loan/Rate/Money → `GAME`，Bank → `BANK`；缺失或无法识别直接抛 `BusinessException`。这是为了防止 Select All（不选具体 process）时 GAME/BANK 数据混在一次响应里返回——见对话最初的要求：game/bank 展示不能串 |
| 删除 | 本次不做。页面本身**无删除入口**，仅为查看；`deleted`/`deletedBy`/`deletedAt` 暂固定回 `false`/`null`，等 Capture Maintenance 的软删归档表落地后再接 |
| 不含分页 | 跟 Payment/Bank Process Maintenance 一样，一个日期范围一次性查完；前端那套日期分片/分页/重试/流式 `onProgress`（服务旧 PHP 分页）后续要跟着简化掉 |

#### 10.1 SQL / 字段来源

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

#### 10.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceTransactionDTO.java`（请求字段 `tenantId`/`dateFrom`/`dateTo`/`process`/`category`/`q` 与响应列共用同一个类，风格对齐 `MaintenancePaymentDTO`） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findTransactionLineMaintenanceRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → `findTransactionLineMaintenanceRows` |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceTransactionsRows`（`TC_ROW_ORDER`：`dtsCreated` desc, `id` desc；`normalizeTransactionCategory` 做 Games/Gambling/Loan/Rate/Money→GAME、Bank→BANK 映射） |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/transaction-maintenance/list`（用户自行实现，已核对） |
| 前端 | `Count-frontend/.../transaction/transactionMaintenanceLogic.js`、`components/TransactionMaintenanceTable.jsx` |

#### 10.3 前端改动（`transactionMaintenanceLogic.js`）

整份重写，对外导出的函数名/签名保持不变（`TransactionMaintenancePage.jsx` 未改一行）：

| 项 | 说明 |
|----|------|
| 请求目标 | `api/transactions/maintenance_search_api.php`（旧 PHP，分页）→ `POST api/maintenance/transaction-maintenance/list`（Spring，一次性返回整段日期范围） |
| 去掉 | 日期分片（`splitMaintenanceDateRange` 等）、分页游标/重试（`fetchAllPagesForRange`/`fetchMaintenancePageWithRetries`）、`appendMaintenanceScopeToParams`（不再传 `company_id`/`view_group`/`group_id`/`report_scope`/`group_only`/`group_aggregate`）、未被任何页面使用的 `packMaintenanceCache`/`getMaintenanceCacheRows`/`isMaintenanceCacheComplete`（已用 grep 确认全仓库无引用） |
| 新增 | `buildSpringTransactionMaintenanceRequest`（组请求体，`category` 缺失直接抛错）、`normalizeSpringTransactionMaintenanceRow`（camelCase → `dts_created`/`id_product`/... 表格字段）、`fetchTransactionMaintenanceOnce`（单租户单次 fetch）、`resolveTransactionMaintenanceTenantId`（从 `scope.scopeCompanyId ?? scope.uiCompanyId` 取 tenantId，取自 `report/shared/reportScope.js` 里 `scopeCompanyId` 字段，各 scope mode 通用） |
| 保留行为 | `scope.mode === "aggregate"`（Group 聚合视图）：仍是前端对 `mergeCompanyIds` 里每个公司循环单租户请求 + 合并排序，只是内层单次请求换成新接口；`onProgress` 在聚合模式下每查完一个公司回调一次，单公司模式下查完整段一次性回调 |
| 行 key 变化 | 旧行用 `transaction_id`/`capture_id`/`capture_detail_id` 三个 id；新行只有一个 `id`（= `data_capture_line.id`）。`TransactionMaintenanceTable.jsx` 的 `getItemKey` 同步从 `row.transaction_id` 改成 `row.id` |
| `q` 搜索 | 不传给后端，跟旧版一样纯前端 `filterTransactionMaintenanceRowsBySearch` 过滤（去掉了字段列表里已不存在的 `from_account`） |

#### 10.3.1 Process 下拉修复（Company 模式抓不到当前公司 process）

`fetchProcessesForMaintenance`（本页 Process 下拉的数据源）原本就有三个分支，Bank category 分支（固定 `SALARY`/`BONUS`/`PROFIT`/`COMMISSION`）和 Group 分支都还在正常工作，**问题出在 Company 模式分支**：一直调用 `maintenanceCompanyApi.js` 里的 `fetchMaintenanceProcesses`，打的是未迁移的旧 PHP `api/processes/processlist_api.php`，导致下拉框空、连带搜索 "No data found"。

修复：Company 模式改调 `pages/processlist/processListApi.js` 的 `fetchProcessListByTenantId(tenantId)`——跟 Process List 页（`docs/process-list-spring-api.md`）同一个 `POST /api/process/process-list`，`normalizeProcessListRows` 已经把 `category === 'BANK'` 的行丢了，返回的就是当前 tenant 下**全部 GAME process**（不筛 status，含 INACTIVE，因为历史数据可能引用已停用的 process）。顺手删掉了这个分支下已经不可达的死代码（`payrollChannel` 在函数最上面已经 return 过一次，走到这里必为 false，原来的 `permForApi`/二次 payroll 过滤永远不会执行）。

范围只改了 `transactionMaintenanceLogic.js` 自己，没碰共享的 `maintenanceCompanyApi.js`——Capture/Payment/BankProcess Maintenance 的 Process 下拉如果有同样问题，需要另外处理，这次没有一并修。

#### 10.3.2 Bank category 查询 "No data found" 修复（category 值传错）

**现象**：Games category 下查询正常；切到 Bank category（payroll-only 公司，如截图里的 OK2），选 SALARY 搜索却 "No data found"，但 `data_capture_line` 表里明明有对应数据。

**根因**：`data_captures.category` 完全由**提交时选中的 process 自己的 `process.category`** 决定（`DataCaptureSummaryServiceImpl.java:427,450` — `isGame = process.getCategory() != BANK`），跟公司是否 payroll-channel 无关；SALARY/BONUS/PROFIT/COMMISSION 属于 BANK 分类的 process，所以这类数据实际存的是 `category='BANK'`。但 `resolveTransactionMaintenanceCategory`（继承自旧 PHP 版本的逻辑）里有一段"payroll-channel/C168 公司选 Bank 时强制发 `category=Games`"的历史兼容代码——旧 PHP 系统里这几个 subsidiary 的 "category" 可能对应完全不同的表/查询，这段兼容跟新 Spring 端 `dc.category` 硬过滤（§10 表格里的必填约定）冲突：查询变成"找 category=GAME 的 SALARY"，实际数据是 BANK，自然查不到。

**排查方式记录**：一开始怀疑是 Process 下拉传值走 `id` 还是 `process.code` 的问题（Bank 分支固定列表 `id`/`process_name` 两个字段本来就是同一个字符串，这个假设不成立），改用 `DataCaptureSummaryServiceImpl` 源码反查 category 写入逻辑才定位到真正原因，不是字段值格式问题，是 category 语义传错。

**修复**：`resolveTransactionMaintenanceCategory` 去掉 payroll-channel/C168 的 Games 覆盖分支，`permission === "bank"` 现在无条件返回 `"Bank"`（→ 后端 `BANK`）。该函数 grep 确认全仓库只有 `searchTransactionData` 一处在用，改动无副作用；签名同步去掉不再需要的 `scope` 参数。

#### 10.3.3 切公司 500（`switch-tenant` 用了 GET，接口只收 POST）

**现象**：在 Transaction Maintenance 页面切换 Company（如 OK1→OK2）时报 "Failed to update session company"，Network 里 `switch-tenant?tenant_id=53` 500。

**根因**（后端日志实锤，非推测）：

```
org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'GET' is not supported
```

`updateSessionCompany`（本页）发的是不带 `method` 的裸 `fetch()`，默认 GET；而 `AuthController.switchTenant` 是 `@PostMapping("/switch-tenant")`（[AuthController.java:107](../backend/src/main/java/com/eazycount/controller/AuthController.java)），只收 POST。跟同一次报告里 `dashboard_bootstrap_api.php` 的 500 是两回事——那个是巧合／另一个独立问题，不是同一根因。

**修复**：`transactionMaintenanceLogic.js` 的 `updateSessionCompany` 加上 `method: "POST"`。

**同款毛病还在别处，本次未修**：`grep switch-tenant` 发现 Payment / BankProcess / Capture / Formula Maintenance 四个页面各自的 `updateSessionCompany`，以及 `UserListPage.jsx`、`useMemberWinLoss.js`，写法跟这次改之前的 Transaction Maintenance 一模一样——同样是裸 `fetch()` 没带 `method: "POST"`。正确写法参考 `utils/auth/authApi.js` 的 `switchSessionTenant` / `utils/company/companySessionSync.js` 的 `syncCompanySessionApi`（都带了 `method: "POST"`）。这些页面切公司大概率会踩到同一个 405/500，需要的话应该是一次性统一补 `method: "POST"`，这次只动了 Transaction Maintenance 范围内的一处。

#### 10.4 待办

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

### 11. Formula Maintenance 数据契约（Spring，List + Edit + Delete 已切换）

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

#### 11.1 SQL / 字段来源

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

#### 11.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceFormulaDTO.java`（请求字段 `tenantId`/`process`/`category`/`q` 与响应列共用同一个类，风格对齐 `MaintenanceTransactionDTO`；无日期范围、无软删占位字段） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findFormulaMaintenanceRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → `findFormulaMaintenanceRows` |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceFormulaRows`（`parseFormulaListQuery` + `FormulaListQuery` record；category 走 `normalizeMaintenanceCategory`，与 Transaction Maintenance 共用同一方法，无额外行排序 comparator——SQL 的 `ORDER BY` 已经按 product 分组排好） |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/formula-maintenance/list` |
| 前端 | `Count-frontend/.../formula/formulaMaintenanceLogic.js`（表格/行组件未改） |

#### 11.3 前端改动（`formulaMaintenanceLogic.js`）

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

#### 11.3.1 Bank category 下 Process 下拉空白修复（`handleClearCompany` 漏传 permission）

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

#### 11.3.2 Bank category 下拉仍然空白：`activePermission` 本身就解析错了

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

#### 11.3.3 真正病根：`resolveFormulaMaintenanceScope` 从来没设置过 `c168Channel`/`companyPayrollChannel`

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

#### 11.4 Edit（Update）API

##### 11.4.1 SQL

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

##### 11.4.2 DTO / Dao / Service / Controller

| 层 | 内容 |
|----|------|
| DTO | `MaintenanceFormulaDTO` 新增 `accountId`（Integer，nullable，update 请求专用，同时也复用为 List 响应列——见 §11.6.1） |
| Dao | `MaintenanceDao.updateFormulaMaintenanceRow(tenantId, id, accountId, sourcePercent, inputMethod, formula, description, updatedBy)` |
| Service | `MaintenanceServiceImpl.updateFormulaMaintenance(ft)`：`requireWritableSession()`（登录 + 非只读，跟 Payment/BankProcess 的 delete 一个套路）→ 校验 `tenantId`/`id`（`requireFormulaTenantId`/`requireFormulaId`）→ `updatedBy` 取 session 的 `login_id`（不接受前端传值）→ `sourcePercent` 走 `normalizeSourcePercent`：空值兜底成 `"0"`（该列是 `NOT NULL DEFAULT '0'`，直接传 null 会撞 DB 约束）；`inputMethod`/`formula`/`description` 复用既有 `normalizeQ`（trim 后空串转 null，这几列本身允许 NULL）。0 行受影响时抛 `BusinessException("Formula maintenance record not found")` |
| Controller | `POST /api/maintenance/formula-maintenance/update`，body 是 `MaintenanceFormulaDTO`，响应 `data` 恒为 `null`（跟 Payment/BankProcess 的 delete 端点一致的"操作类接口不回数据"风格） |

##### 11.4.3 前端（`formulaMaintenanceLogic.js`）

| 项 | 说明 |
|----|------|
| 端点 | `api/formula_maintenance/update_api.php`（旧 PHP）→ `POST api/maintenance/formula-maintenance/update` |
| 请求体 | `buildSpringFormulaMaintenanceUpdateRequest({tenantId, id, accountId, sourcePercent, inputMethod, formula, description})`，`tenantId`/`id` 缺失直接抛错 |
| tenantId 解析 | 跟 List 一样用 `formulaMaintenanceEffectiveCompanyId(scope, companyId)`，调用点 `FormulaMaintenancePage.jsx` 的 `handleSaveRow` 不再手搓 `template_id`/`company_id` 那套旧 payload |
| **行为修正（重要）** | 老版本 Formula 列展示是"base 公式 + `*(source)`"拼接出来的派生显示串，编辑框里改 Source % 会连带重写 Formula 文本框内容（`syncEditFormSourcePercent` 原来会顺手改 `formula`）。但新后端 `formula`/`source_percent` 是两个独立列，各自展示各自存（§11.1 已确认 Formula 列展示的就是原始 `formula` 字段，不是拼接串）。继续用旧逻辑会把一段 `*(0.75)` 文本永久写死进 `formula` 字段存进 DB，数据会被污染。所以把这个副作用去掉了：现在改 Source % 只改 `source_percent`，Formula 框保持用户自己输入的原始文本，两者互不干扰 |
| `patchFormulaRowAfterSave` | 去掉了 `serverData` 参数——新后端 update 成功后 `data` 恒为 `null`，没有服务器回填字段；改成保存成功后直接把 `editForm` 里的值乐观地贴回本地行 |
| `createFormulaEditFormFromRow` | 去掉了 `source_ref`（对应旧的 `source_columns`，这次 Edit 范围明确不可编辑，UI 上也从来没有对应输入控件，纯粹是老代码的死重量） |
| 删掉的死函数 | `buildEditFormFormulaDisplay`/`resolveFormulaBaseFromRow`/`parseFormulaEditTail`/`buildFormulaEditString`——全仓库 grep 确认除定义处外无其它引用，安全删除 |

#### 11.5 Delete API

##### 11.5.1 SQL

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

##### 11.5.2 DTO / Dao / Service / Controller

| 层 | 内容 |
|----|------|
| DTO | `MaintenanceFormulaDTO` 新增 `formulaIds`（`List<Integer>`，delete 请求专用；命名对齐 `MaintenancePaymentDTO`/`MaintenanceBankProcessDTO` 的 `transactionIds` 风格） |
| Dao | `MaintenanceDao.deleteFormulaMaintenanceRows(tenantId, ids)` |
| Service | `MaintenanceServiceImpl.deleteFormulaMaintenance(ft)`：`requireWritableSession()` → 校验 `tenantId` → `requireFormulaIds` 拿非空 id 列表 → 调 Dao 批量硬删；0 行受影响抛 `BusinessException("No matching formula maintenance records to delete")`。跟 Payment/BankProcess 的删除不一样：没有 archive 步骤，也没有 RATE/bank-process 那种级联展开逻辑 |
| Controller | `POST /api/maintenance/formula-maintenance/delete`，body 是 `MaintenanceFormulaDTO`，响应 `data` 恒为 `null` |

##### 11.5.3 前端

| 项 | 说明 |
|----|------|
| 端点 | `api/formula_maintenance/delete_api.php`（旧 PHP）→ `POST api/maintenance/formula-maintenance/delete` |
| 请求体 | `deleteFormulaTemplates({tenantId, formulaIds})`，内部校验非空 id 数组，`tenantId` 用 `formulaMaintenanceEffectiveCompanyId(scope, companyId)` 算 |
| 调用点 | `FormulaMaintenancePage.jsx` 的 `handleConfirmDelete`，不再传 `effectiveCompanyId`/`scope` 两个参数给 `deleteFormulaTemplates`，改传 `{tenantId, formulaIds: idsToDelete}` |

#### 11.6 账户下拉两处修复

##### 11.6.1 List 响应补 `accountId`（数值 FK），Edit 才能正确回显选中账户

**问题**：List 的 `findFormulaMaintenanceRows` 原本只 `SELECT a.account_id AS account`（账户业务码字符串，如 `OK`），没有 `data_capture_formula.account_id` 本身（数值 FK）。点 Edit 时账户下拉的 `<option value={acc.id}>` 用的是数值 id，没有这个字段就没法正确预选中当前行的账户。

**修复**：`MaintenanceMapper.xml` 的 `findFormulaMaintenanceRows` SELECT 里加一行 `f.account_id AS accountId`，复用 `MaintenanceFormulaDTO` 已有的 `accountId` 字段（原本只给 update 请求用）——跟 `process` 字段"请求参数 + 响应列复用同一个字段"是同一个惯例。这是在"只改前端"的那一轮里顺手做的最小后端补丁（一行 SELECT，没碰 Dao 签名/Service/Controller），当时已明确告知用户。

前端 `normalizeSpringFormulaMaintenanceRow` 相应加了 `account_id: row.accountId ?? null`，`createFormulaEditFormFromRow` 直接读 `row.account_id` 回填编辑框。

##### 11.6.2 账户下拉本身是空的：`fetchAccounts` 迁移到 Spring `/api/account/list`

**现象**：点 Edit，账户下拉框只有 "Select Account" 占位，没有任何账户选项。

**根因**：`fetchAccounts` 跟 List/Update/Delete 迁移前的其它函数一样，还打着旧 PHP `api/transactions/get_accounts_api.php`——反向代理把所有 `/api/*` 转发给 Spring 后，这条没实现过的路由必然 500。

**修复**：改用 `pages/account/accountListApi.js` 的 `fetchAccountListByTenantId(tenantId)`（`POST /api/account/list`，Spring，tenant-scoped）+ `filterAccountListRows(rows)`（默认只留 active 账户，跟旧接口 `status=active` 参数行为一致）。这个 Spring 账户接口不是新写的，是复用现成的——Data Capture Summary 的 Edit Formula 账户下拉（`datacapturesummary/lib/summaryApi.js`）、Transaction 页的 `AccountSelect`（`transaction/lib/transactionAccountHelpers.js`）都已经在用。新增 `normalizeFormulaAccountOption`，把 Spring 返回的 `{id, account_id, name}` 拼成表格下拉需要的 `{id, account_id, display_text}`，`display_text` 格式 `"CODE (Name)"` 照抄 `transactionAccountHelpers.js` 的 `normalizeTransactionAccountOption`，保持全仓库账户下拉文案风格一致。

`fetchAccounts(companyId, scope)` 函数签名没变（内部改成 `formulaMaintenanceEffectiveCompanyId(scope, companyId)` 解析 `tenantId`），`FormulaMaintenancePage.jsx` 里 4 处调用点不用改。顺手删掉了只服务这条旧路径的 `appendFormulaScopeToParams` 函数和不再需要的 `formulaMaintenanceScopeApiParams` 导入。

#### 11.7 待办

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

### 12. Capture Maintenance 数据契约（Spring，List + Delete 全链路已切换，行粒度为 capture）

只读列表。**行粒度改过一次**：最初做的是"一行 = 一条 `data_capture_line`"（一个 capture 里每个 Product 各占一行），用户看实机效果后纠正为"一行 = 一条 `data_captures`"（一个 capture/一次提交只占一行，不展开到 Product 明细），已经按新粒度重做完。跟 §10 Transaction Maintenance 同一套表、同一个 `MaintenanceDao`/`MaintenanceMapper.xml`（未新建 Dao/Mapper 文件）。**当前进度：DTO + Dao + Mapper SQL + Service + Controller + 前端全部按新粒度对齐完成；Delete 走 Spring（新粒度）。**

#### 12.1 字段来源（capture 级别，已重做）

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

#### 12.1.1 连带简化：删除不用再"选中行反查 capture"了

因为列表本身已经是一行一个 capture，前端勾选的行 id 直接就是 `data_captures.id`，**`findCaptureIdsByLineIdsAndTenantId` 这个中间转换方法整个删掉了**，`MaintenanceCaptureDTO.lineIds` 也改名成 `captureIds`（不再需要"选中的 line id → distinct capture_id"这一步）。`deleteMaintenanceCaptureRows` 现在直接拿 `mc.captureIds` 进级联（归档 line → 联动归档/硬删 transaction → 硬删 line → 删 process_submitted），比上一版少一次查询往返。

#### 12.2 关键文件索引

| 层 | 路径 |
|----|------|
| DTO | `backend/.../dto/MaintenanceCaptureDTO.java`（请求字段 `tenantId`/`dateFrom`/`dateTo`/`process`/`category`/`q`/`captureIds`(删除用) 与响应列共用同一个类，风格对齐 `MaintenanceTransactionDTO`） |
| Dao | `backend/.../dao/MaintenanceDao.java` → `findCaptureLineMaintenanceRows`/`findCaptureLineMaintenanceDeletedRows` |
| Mapper | `backend/.../resources/mybatis/MaintenanceMapper.xml` → 同名两条 SQL + `captureDescriptionAgg`/`captureProductExpr` 两个共用 `<sql>` 片段 |
| Service | `backend/.../service/MaintenanceService.java` / `impl/MaintenanceServiceImpl.java` → `findMaintenanceCaptureRows`（`CC_ROW_ORDER`：`dtsCreated` desc, `id` desc；复用 §11 的 `normalizeMaintenanceCategory`）+ `deleteMaintenanceCaptureRows` |
| Controller | `backend/.../controller/MaintenanceController.java` → `POST /api/maintenance/capture-maintenance/{list,delete}`（用户自行实现，已核对） |
| 前端 | `Count-frontend/.../capture/captureMaintenanceLogic.js`（`CaptureMaintenancePage.jsx`/`CaptureVirtualRows.jsx`/`CaptureVirtualDataRow.jsx` 均未改一行——`row.capture_id` 字段名从一开始就是通用命名，现在后端 `id` 语义变成 capture 级别之后天然对得上，不用改前端展示层） |

#### 12.3 前端改动（`captureMaintenanceLogic.js`）

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

#### 12.4 待办

- [x] DTO/Dao/Mapper：`MaintenanceCaptureDTO` + `findCaptureLineMaintenanceRows`
- [x] Service：`findMaintenanceCaptureRows` + `parseCaptureListQuery`（`CaptureListQuery` record），跟 `findMaintenanceTransactionsRows` 同一套写法，`category` 复用 `normalizeMaintenanceCategory`
- [x] Controller：`POST /api/maintenance/capture-maintenance/list`（无 delete 端点）
- [x] 前端 `captureMaintenanceLogic.js` 切到新接口，Process 下拉换 Spring process-list，`updateSessionCompany` 补 `method: "POST"`
- [x] 删除功能全链路已实现（schema/Dao/Service/Controller/前端，见 §12.5），已按"一行一个 capture"的新粒度重做过一次
- [ ] 实机验证：能查到数据、Product/W-L Group 列在 GAME 无 description 时正确回退成 process code、Bank category（payroll-only 公司）和 Games category 都能各自查到数据不混列、**尤其要验证 §12.3 里 `category` 推断逻辑对不对**（有没有 Game 权限公司需要看 Bank 数据的场景）、多个不同 Product 属于同一 capture 时列表正确合并成一行、删除后列表正确标红
- [ ] §10.3.3 提到的其余几处（Payment/BankProcess Maintenance + UserListPage + useMemberWinLoss）仍未修，需要的话再统一处理

#### 12.5 删除级联设计（schema + Service + Controller + 前端均已实现）

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

#### 12.6 Submit 流程回填 `transaction_id` / `capture_id`（已完成）

`DataCaptureSummaryServiceImpl.submit()`（[DataCaptureSummaryServiceImpl.java:462-479](../backend/src/main/java/com/eazycount/service/impl/DataCaptureSummaryServiceImpl.java)）调整了插入顺序：

- 原来：每行先 `toLineEntity(...)` 建好丢进 `lineEntities` 列表，金额非0再 `transactionDao.insert(...)`——line 实体建好时根本不知道自己的 transaction id 是多少。
- 现在：金额非0先 `transactionDao.insert(txn)`（`TransactionMapper.xml` 的 `insert` 本来就是 `useGeneratedKeys="true" keyProperty="id"`，insert 完 `txn.getId()` 立刻能拿到），把这个 id 传给 `toLineEntity(..., lineTransactionId)`，再统一批量插入 `data_capture_line`。金额为 0 的行 `lineTransactionId` 传 `null`（`data_capture_line.transaction_id` 本来就允许 NULL）。
- `insertProcessSubmitted` 调用加了 `captureId` 参数（`header.getId()` 在这行执行前早就有了，改起来比 transaction_id 简单，插入顺序都不用动）。

配套改动：`DataCaptureLine` 实体加 `transactionId` 字段；`DataCaptureSummaryMapper.xml` 的 `insertLines`（写入）和 `findLinesByCaptureId`（读取，供其它地方回填用）都加了 `transaction_id` 列；`DataCaptureDao.insertProcessSubmitted` 签名加 `captureId` 参数，`DataCaptureMapper.xml` 对应 INSERT 加 `capture_id` 列——全仓库只有 `DataCaptureSummaryServiceImpl.submit()` 一处调用，改签名不影响别处。

**至此，新提交的数据会自动带上 `transaction_id`/`capture_id`，Capture Maintenance 删除时的三处联动（transactions / process_submitted / 归档表）对新数据完全生效**。历史数据（这次改动前提交的）两个字段仍是 NULL，删历史 capture 时只能软删 capture 侧本身，联动不了 transaction/process_submitted——这是之前就确认过、无法回填的已知限制。

#### 12.7 前端删除确认弹窗文案（已完成）

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

### 13. 已知待修问题（2026-08-13 复查记录）

对五个 Maintenance 页面（Payment / Bank Process / Capture / Formula / Transaction）做了一轮 company/games-bank 相关的复查（group 部分未看）。核心 List/Edit/Delete 端点确认全部已在 Spring，没有整支功能漏做的页面；但发现下面几个仍未修的具体缺口，先记录，不在这轮动手改。

#### 13.1 Payment / Bank Process Maintenance 切公司仍是裸 GET（405/500）

`updateSessionCompany` 在 Capture / Formula / Transaction 三页已经补上 `method: "POST"`（见 §10.3.3），但 **Payment 和 Bank Process 这两页还没修**：

- `Count-frontend/src/pages/maintenance/payment/paymentMaintenanceLogic.js:230` —— 裸 `fetch(...)`，无 `method`，默认 GET
- `Count-frontend/src/pages/maintenance/bankprocess/bankprocessMaintenanceLogic.js:310` —— 同上

`AuthController.switchTenant` 只收 POST，这两页切公司会 405/500。修法就是照抄 Capture/Formula 已经改好的写法加一行 `method: "POST"`。

#### 13.2 `api/domain/domain_api.php` 从未在 Spring 实现，Capture/Formula/Transaction 仍在调用

`maintenanceCompanyApi.js` 的 `fetchDomainCompanyPermissions` 打的是 `api/domain/domain_api.php`，Spring `DomainController` 没有对应路由（只有 `/list`/`/add`/`/update`/`/delete`/`/list-fee`/`/add-fee`，没有 `get_company_permissions` 这个 action），必然 500，catch 后静默 fallback 成写死的默认权限列表（`["Games","Bank",...]`，Games 排最前）。

- Formula/Transaction 已经靠繞開這条路径解决了实际症状（改读 scope 上的 `c168Channel`/`companyPayrollChannel`，不再依赖这个权限列表本身），但底层这个坏接口没有被替换，只是被绕开——如果以后有新逻辑直接信了这个权限列表的值，会重新踩坑。
- 顺手确认：`paymentMaintenanceLogic.js` 里的 `fetchCompanyPermissions`（同样打这个坏接口）在 Payment Maintenance 页面里全仓库 grep 确认零调用，是死代码，目前无实际影响。
- 同文件里的 `fetchMaintenanceProcesses`（打另一个旧 PHP `api/processes/processlist_api.php`）也是全仓库零调用的死代码。
- 三个都在 `Count-frontend/src/pages/maintenance/shared/maintenanceCompanyApi.js`。

#### 13.3 Capture Maintenance 的 category 二选一逻辑覆盖不了「Game+Bank 都有权限」的公司

`Count-frontend/src/pages/maintenance/capture/captureMaintenanceLogic.js` 的 `resolveCaptureMaintenanceCategory`：

```js
function resolveCaptureMaintenanceCategory(scope) {
  const payrollChannel = Boolean(scope?.c168Channel || scope?.companyPayrollChannel);
  return payrollChannel ? "Bank" : "Games";
}
```

`category` 在 SQL 里是必填硬条件（§12.1，防 Select All 时 GAME/BANK 混列），所以选错等于那部分数据对这页面完全不可见。如果一间公司同时有 Game 和 Bank 权限、但没被判定成 C168 或 bank-only（没打上 `companyPayrollChannel`），这页会永远只查 Games 分类，Bank 分类的 capture 记录完全查不到，UI 上也没有手动切换的地方。

已核对过 `captureMaintenanceScope.js` 的 enrich 逻辑本身没问题（跟 Transaction Maintenance 抄的是同一份写法），不是 §11.3.3 那种"忘记补 flag"的重演，是这个二分类设计本身覆盖不了"两种权限都有"的公司——跟文件 §12.3/§12.4 当初标注的「⚠️ 推断，需要实机验证」是同一个未解决项，复查后确认到现在仍未修。

#### 13.4 Transaction Maintenance 看不到已删除行 —— **非缺口，用户已确认不需要**

`findTransactionLineMaintenanceRows` 没有接 `data_capture_line_deleted` 归档表的 UNION 查询，跟 Capture Maintenance 的 live+archived 合并不同步（§10.4、§12.7 曾记过这项 TODO）。**2026-08-14 用户明确决定**：Transaction Maintenance 只需要看有数据（活跃）的行，不需要展示已删除行，这项不用做，§10.4/§12.7 里的对应 TODO 已作废。

#### 13.5 待办清单

- [ ] `paymentMaintenanceLogic.js` / `bankprocessMaintenanceLogic.js` 的 `updateSessionCompany` 补 `method: "POST"`（见 §13.1）
- [ ] 实机验证一间「Game+Bank 都有权限、非 C168/非 bank-only」的公司，确认 §13.3 的 Capture Maintenance Bank 数据不可见问题是否真的复现
- [ ] 视 §13.3 验证结果决定怎么修 `resolveCaptureMaintenanceCategory`（可能需要 UI 加分类切换，而不是纯二选一自动判断）
- [ ] `api/domain/domain_api.php` 要嘛在 Spring 补一个真正的权限查询端点，要嘛把 `fetchDomainCompanyPermissions`/`fetchCompanyPermissions`（Payment 死代码）/`fetchMaintenanceProcesses`（死代码）一并从 `maintenanceCompanyApi.js` 清掉（见 §13.2）
- [x] ~~`findTransactionLineMaintenanceRows` 补 `data_capture_line_deleted` 归档表 UNION~~ —— 用户已确认不需要，Transaction Maintenance 只看活跃行即可（见 §13.4）

---

## 19. Payment Maintenance — List / Delete（Spring）

> 原始独立文件：`docs/payment-maintenance-list-delete.md`（内容已合并于此；原文件已改为跳转说明）


Payment Maintenance 页面的列表与软删除约定。修改 API、过滤规则、`transactions_deleted` 表结构或前端契约时，**同步更新本文档**。

Maintenance 侧边栏（含 Bank Process 入口）：[`maintenance-navigation.md`](./maintenance-navigation.md)

### 1. 范围与原则

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/payment/*` |
| 数据源 | `transactions`（活数据）+ `transactions_deleted`（软删归档） |
| 租户 | **一律 `tenantId`**（公司 pill 的 numeric id = tenant.id） |
| 不做 | 不再传 / 校验 `company_id`、`group_id`、`view_group`、`report_scope`、`group_aggregate` 等 scope 参数 |
| 不含 | Bank Process 入账行（`bank_process_posted_id IS NOT NULL`） |
| 含 | 手动交易 + Domain / Renew 相关流水（类型见下；Domain/Renew 一般为 `PAYMENT`） |
| 契约方向 | **前端对齐后端**（camelCase JSON / `tenantId`），不为兼容旧 PHP 再造字段 |

Payment 与 Bank Process Maintenance **共用** `transactions_deleted`；Formula Maintenance 为硬删，**不**进本表。

Bank Process Maintenance 列表/删除：[`bankprocess-maintenance-list-delete.md`](./bankprocess-maintenance-list-delete.md)

### 2. 允许的 `transaction_type`

```text
PAYMENT, CLAIM, CLEAR, CONTRA, RATE, ADJUSTMENT, PROFIT
```

- **已移除遗留类型 `RECEIVE`**（entity / schema / 前端筛选与提交守卫均已去掉）。
- **不含** `WIN` / `LOSE`（Bank Process Win/Loss，走 Bank Process Maintenance）。

Service 层：`MaintenanceServiceImpl.ALLOWED_TYPES` 与 Mapper fragment `paymentMaintenanceTransactionTypes` 保持一致。

### 3. 数据库

#### 3.1 `transactions_deleted`（优化后）

归档表：软删时先拷贝再从 `transactions` 物理删除。列表仍可展示划线行 + Deleter。

定义见：

- [`backend/src/main/resources/sql/schema.sql`](../backend/src/main/resources/sql/schema.sql)
- [`backend/src/main/resources/schema.sql`](../backend/src/main/resources/schema.sql)

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | INT PK AI | 归档行主键 |
| `transaction_id` | INT NOT NULL | 原 `transactions.id` |
| `tenant_id` | INT NOT NULL | 租户（取代旧 `company_id`） |
| `transaction_type` | ENUM(...) | 与主表一致（无 `RECEIVE`） |
| `account_id` | INT NOT NULL | To Account |
| `from_account_id` | INT NULL | From Account |
| `currency_id` | INT NULL | 币种 |
| `amount` | DECIMAL(25,8) | 金额 |
| `transaction_date` | DATE | 交易日（列表日期筛选用此字段） |
| `description` | VARCHAR(500) | 描述 |
| `remark` | VARCHAR(500) | 备注（取代旧 `sms`） |
| `created_by` | VARCHAR(100) | 原提交人 **login_id** |
| `created_at` | TIMESTAMP | 原创建时间（列表 Created At） |
| `deleted_by` | VARCHAR(100) | 删除人 **login_id**（UI Deleter） |
| `deleted_at` | TIMESTAMP | 删除时间 |
| `bank_process_posted_id` | INT NULL | `NULL` = Payment Maintenance；有值 = BP Maintenance 归档 |
| `rate_group_id` | VARCHAR(50) NULL | RATE 组（如有） |

索引：

- `(tenant_id, transaction_date)`
- `(transaction_id)`
- `(deleted_at)`
- `(tenant_id, bank_process_posted_id)`

Entity：[`TransactionDeleted.java`](../backend/src/main/java/com/eazycount/entity/TransactionDeleted.java)

#### 3.2 相对旧 PHP 表的变更

| 旧列 / 概念 | 新约定 |
|-------------|--------|
| `company_id` | → `tenant_id` |
| `scope_type` / `scope_id` | **删除**（API 不再使用 scope） |
| `sms` | → `remark` |
| `created_by` / `created_by_owner`（数字 user/owner id） | → `created_by`（login_id 字符串） |
| `deleted_by_user_id` / `deleted_by_owner_id` | → `deleted_by`（login_id） |
| `source_bank_process_id` | → `bank_process_posted_id`（对齐 Spring 主表） |
| `source_bank_process_period_type` | **删除**（当前归档不存） |
| enum 含 `RECEIVE` | **去掉 RECEIVE**；补齐 `CLAIM` / `CLEAR` / `PROFIT` 等 |

#### 3.3 相关迁移脚本

| 文件 | 用途 |
|------|------|
| `sql/migrate_transaction_type_add_profit.sql` | 主表 type 增加 `PROFIT`（enum 已无 RECEIVE） |
| `sql/migrate_transaction_type_drop_receive.sql` | 已上线库从 enum 去掉 `RECEIVE`（执行前确认无 `RECEIVE` 行） |
| `sql/migrate_transactions_deleted_created_by_login_id.sql` | 归档表 `created_by` / `deleted_by` 改为 VARCHAR login_id（旧库 INT 会导致 archive 失败） |

已有旧库升级到新 `transactions_deleted` 时，需单独做数据迁移（`company_id`→`tenant_id`、`sms`→`remark`、deleter 字段合并等），不能只改 schema 定义。

### 4. 架构分层

```text
PaymentMaintenancePage (UI: Group/Company pills)
  → resolvePaymentMaintenanceTenantId()  // 仅得到 tenantId
  → POST /api/maintenance/payment-maintenance/list|delete

MaintenanceController
  → MaintenanceService / MaintenanceServiceImpl
      → TransactionDao + TransactionMapper.xml
          → transactions / transactions_deleted
```

- **SQL / Dao**：留在 Transaction 模块（数据归属 `transactions`）。
- **Controller / Service**：挂在 Maintenance 用例门面（`/api/maintenance/...`）。
- **不**把交易 SQL 塞进与跑马灯同名的旧 Maintenance 实体。

### 5. List API

#### 5.1 端点

`POST /api/maintenance/payment-maintenance/list`

#### 5.2 请求（`PaymentMaintenanceRequest`）

```json
{
  "tenantId": 12,
  "dateFrom": "24/07/2026",
  "dateTo": "24/07/2026",
  "transactionType": "PAYMENT",
  "currencyCodes": ["MYR"],
  "q": "ABC"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | `> 0` |
| `dateFrom` / `dateTo` | 是 | `dd/MM/yyyy` 或 `yyyy-MM-dd`；闭区间；`dateTo >= dateFrom` |
| `transactionType` | 否 | 空 / null = 全部允许类型；非法值抛错 |
| `currencyCodes` | 否 | 空数组 = 全部币种；元素会规范化为大写 |
| `q` | 否 | 模糊匹配 To/From account code、description、remark、createdBy |

日期过滤字段：`transaction_date`（不是 `created_at`）。  
展示列 Created At 仍来自 `created_at`。

#### 5.3 列表 SQL 过滤（活数据）

Dao：`findPaymentMaintenanceRows`

- `tenant_id = #{tenantId}`
- `bank_process_posted_id IS NULL`
- `approval_status = 'APPROVED'`
- `transaction_type IN (paymentMaintenanceTransactionTypes)`
- `transaction_date` 闭区间 + 可选 type / currency / `q`
- `ORDER BY created_at DESC, id DESC`

#### 5.4 响应行（`PaymentMaintenanceRow`）

| JSON 字段 | 含义 |
|-----------|------|
| `id` | 活数据为 `transactions.id`；归档行为原 `transaction_id` |
| `transactionType` | 类型 |
| `createdAt` | Created At |
| `toAccountCode` / `fromAccountCode` | Account(To) / Account(From) |
| `amount` / `currencyCode` | 金额与币种 |
| `description` / `remark` | 描述 / 备注 |
| `createdBy` | Submitter（login_id） |
| `deleted` | `false` 活数据；`true` 归档 |
| `deletedBy` / `deletedAt` | Deleter；活数据为 null |

Envelope：

```json
{
  "success": true,
  "message": "Payment maintenance list retrieved",
  "data": [ /* PaymentMaintenanceRow[] */ ]
}
```

#### 5.5 前端 List

- 文件：`Count-frontend/src/pages/maintenance/payment/paymentMaintenanceLogic.js`
- `buildSpringPaymentMaintenanceRequest` → 只组后端字段
- `normalizeSpringPaymentMaintenanceRow` → 表格用 `dts_created` / `account` / `from_account` / `is_deleted` / `deleted_by` / `dts_deleted` 等
- 币种：`POST /api/currency/list?tenant_id=`
- 公司列表：`fetchOwnerCompaniesAll`（Spring tenant-accessible）

#### 5.6 List 合并归档行（软删 UI）

`MaintenanceServiceImpl.findPaymentMaintenanceRows` 并行查 live + archived，合并后按 `createdAt` / `id` 降序：

1. `findPaymentMaintenanceRows` — 活数据（`deleted=false`）
2. `findPaymentMaintenanceDeletedRows` — `transactions_deleted`（`deleted=true`，含 `deletedBy` / `deletedAt`）

删除后行仍出现在列表：前端 `maintenance-row-deleted` 划线样式，Deleter 列显示 `{deletedBy} ({deletedAt})`，已删行不可再勾选（`isPaymentMaintenanceRowSelectable` → false）。

### 6. Delete API（软删）

#### 6.1 行为（与旧 PHP 一致的语义）

`MaintenanceServiceImpl.deletePaymentMaintenanceRows`（`@Transactional`）：

1. 校验登录、非只读、`tenantId`、`transactionIds`
2. `resolveDeletableBatch`：加载选中行 → 过滤可删（`bank_process_posted_id IS NULL` 且 type ∈ 允许列表）
3. **RATE 扩展**：选中行含 `rate_group_id` 时，扩展为同组全部 leg（含 Middle-Man fee）；收集 `rateGroupIds`
4. **`INSERT … SELECT` → `transactions_deleted`**（`archivePaymentMaintenanceToDeleted`；`deleted_by` = session `login_id`，`deleted_at` = NOW()）
5. **`DELETE FROM transactions_rate`**（若有 `rateGroupIds`；解除 `leg1_transaction_id` / `leg2_transaction_id` FK）
6. **`DELETE FROM transactions`**（`deleteByIdsAndTenantId`）
7. 任一步失败整段回滚

不是主表加 `deleted_at` 的原地软删；主表物理删除以保证 Transaction Search / History / 余额不易误算。列表靠 §5.6 合并 `transactions_deleted` 展示划线行。

##### RATE 删除注意

| 项 | 说明 |
|----|------|
| FK | `transactions_rate.leg1/leg2_transaction_id` → `transactions.id`；必须先删 rate header |
| 扩展 | 删一条 RATE leg 会 archive + 删除整组同 `rate_group_id` 行 |
| Dao | `findPaymentMaintenanceIdsByRateGroupIds`、`TransactionRateDao.deleteByTenantIdAndRateGroupIds` |

#### 6.2 端点

`POST /api/maintenance/payment-maintenance/delete`

#### 6.3 请求（`PaymentMaintenanceDeleteRequest`）

```json
{
  "tenantId": 12,
  "transactionIds": [101, 102, 103]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | `> 0` |
| `transactionIds` | 是 | 活数据 `transactions.id`；前端去重且仅正整数 |

`deleted_by` **不由前端传**，取自 session `login_id`。

#### 6.4 响应

成功（当前 Controller）：

```json
{
  "success": true,
  "message": "Process deleted successfully",
  "data": null
}
```

Service 内部有 `PaymentMaintenanceDeleteResult.deleted`（实际删除条数）；若需返回给前端，可在 Controller 把 `data` 设为该 result。

失败：`success: false`，`message` 为业务错误文案，例如：

- `Not logged in`
- `Read-only access cannot delete transactions`
- `Invalid tenant id`
- `Please select at least one record`
- `No matching payment maintenance records to delete`
- `Failed to archive / delete payment maintenance records`

#### 6.5 前端 Delete

- `buildSpringPaymentMaintenanceDeleteRequest({ tenantId, transactionIds })`
- `deletePaymentRecords` → `POST api/maintenance/payment-maintenance/delete`
- 页面用 `activeTenantId`（`resolvePaymentMaintenanceTenantId`）
- 已删行：`isPaymentMaintenanceRowSelectable` 返回 false（不可再勾选）
- 成功后清空选中并重新 list

### 7. 关键文件索引

| 层 | 路径 |
|----|------|
| Controller | `backend/.../controller/MaintenanceController.java` |
| Service | `backend/.../service/MaintenanceService.java` |
| ServiceImpl | `backend/.../service/impl/MaintenanceServiceImpl.java` |
| DTO | `TransactionDTO.PaymentMaintenanceRequest` / `DeleteRequest` / `DeleteResult` / `PaymentMaintenanceRow` |
| Entity | `TransactionDeleted.java` |
| Dao / Mapper | `TransactionDao.java`，`TransactionMapper.xml`（含 archive / delete / list / deleted list） |
| Schema | `resources/sql/schema.sql`，`resources/schema.sql` |
| Frontend | `Count-frontend/.../payment/paymentMaintenanceLogic.js`，`PaymentMaintenancePage.jsx` |

### 8. 变更时检查清单

- [ ] 允许类型是否前后端 + Mapper fragment 三处一致  
- [ ] List / Delete 是否仍只使用 `tenantId`（无 scope 回归）  
- [ ] `transactions_deleted` 列是否与 `TransactionDeleted` entity / INSERT SELECT 一致  
- [ ] 去掉或新增 type 时是否更新 enum 迁移脚本  
- [ ] List 若需软删划线展示：Service 是否已 merge live + `findPaymentMaintenanceDeletedRows`（已实现）
- [ ] Delete RATE 行：是否扩展 rate group + 先删 `transactions_rate`（已实现）
- [ ] 前端归一化是否覆盖 `deleted` / `deletedBy` / `deletedAt`
- [ ] Bank Process Maintenance 侧边栏：是否用 `tenant_has_bank`（见 `maintenance-navigation.md`）

---

## 20. Bank Process Maintenance — List / Delete（Spring）

> 原始独立文件：`docs/bankprocess-maintenance-list-delete.md`（内容已合并于此；原文件已改为跳转说明）


Bank Process Maintenance 页面列表与软删除约定。修改 API、过滤规则或前端契约时，**同步更新本文档**。

相关：

- Payment Maintenance（对照实现）：[`payment-maintenance-list-delete.md`](./payment-maintenance-list-delete.md)
- 侧边栏入口：[`maintenance-navigation.md`](./maintenance-navigation.md)

---

### 1. 范围与原则

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/bankprocess/*` |
| 数据源 | `transactions`（活数据）+ `transactions_deleted`（软删归档） |
| 租户 | **一律 `tenantId`**（Company pill numeric id） |
| **含** | Bank Process **已入账**行：`bank_process_posted_id IS NOT NULL`，`transaction_type IN (WIN, LOSE)`，`bpap.outcome = POSTED` |
| **不含** | Payment Maintenance 手动流水（`bank_process_posted_id IS NULL`） |
| 归档 | 与 Payment 共用 `transactions_deleted`（`bank_process_posted_id` 有值 = BP Maintenance 归档） |

---

### 2. 列表 UI 列（与旧 PHP / 截图一致）

| 表头 | 字段来源 |
|------|----------|
| No. | 行号 |
| Dts Created | `created_at` |
| Account | To account code（`toAccountCode`） |
| From | From account code；无则 `bank_process.card_owner` |
| Amount | `currencyCode` + `amount` |
| Description | `description` |
| Remark | `remark` |
| Submitted By | `createdBy`（login_id） |
| Deleter | 软删行：`{deletedBy} ({deletedAt})` |
| Checkbox | 活数据可勾选；**同 Post 批次**联动勾选（见前端 batch key） |

软删行：`deleted=true` → 红色划线（`maintenance-row-deleted`），`is_deleted=1`，不可再勾选。

**无 Category pills**（见 `maintenance-navigation.md` §9）。

---

### 3. List API

#### 3.1 端点

`POST /api/maintenance/bankprocess-maintenance/list`

#### 3.2 请求（`BankProcessMaintenanceRequest`）

```json
{
  "tenantId": 12,
  "dateFrom": "01/01/2026",
  "dateTo": "24/07/2026",
  "currencyCodes": ["MYR"],
  "q": "CIMB"
}
```

无 `transactionType`（Bank Process Maintenance 固定 WIN/LOSE 入账行）。

| 字段 | 说明 |
|------|------|
| `tenantId` | 必填，`> 0` |
| `dateFrom` / `dateTo` | 闭区间；过滤 **`transaction_date`** |
| `currencyCodes` | 空数组 = 全部币种 |
| `q` | 模糊：Account / From / card_owner / description / remark / submitter（归档含 deleter） |

#### 3.3 响应行（`BankProcessMaintenanceRow`）

| JSON 字段 | 含义 |
|-----------|------|
| `id` | 活数据 `transactions.id`；归档为原 `transaction_id` |
| `transactionType` | `WIN` / `LOSE` |
| `createdAt` | Dts Created |
| `toAccountCode` | Account 列 |
| `fromAccountCode` | From 列 |
| `amount` / `currencyCode` | Amount |
| `description` / `remark` | 描述 / 备注 |
| `createdBy` | Submitted By |
| `deleted` / `deletedBy` / `deletedAt` | 软删展示 |
| `bankProcessId` | `bank_process.id`（前端 `source_bank_process_id`，批次勾选） |
| `periodType` | `bank_process_accounting_posted.period_type` |
| `transactionDate` | `transaction_date`（批次 key 之一） |

Service 合并 live + archived，按 `createdAt` / `id` 降序。

#### 3.4 前端

- `bankprocessMaintenanceLogic.js`
  - `buildSpringBankprocessMaintenanceRequest`
  - `normalizeSpringBankprocessMaintenanceRow`
  - `searchBankprocessData` → Spring list
  - `bankprocessMaintenanceBatchKey` / `toggleBankprocessMaintenanceBatchSelection`
- 币种：`POST /api/currency/list?tenant_id=`

---

### 4. Delete API（软删）

#### 4.1 行为

`MaintenanceServiceImpl.deleteBankProcessMaintenanceRows`（`@Transactional`）：

1. 校验登录、非只读、`tenantId`、`transactionIds`
2. `resolveBankProcessDeletableBatch`：加载选中行 → 过滤可删（`bank_process_posted_id IS NOT NULL`、`WIN`/`LOSE`、`approval_status = APPROVED`）
3. **Post 批次扩展**：选中行含 `bank_process_posted_id` 时，扩展为同 posted id 全部 WIN/LOSE 行（一次 Post 的多条流水一并归档删除）
4. **`INSERT … SELECT` → `transactions_deleted`**（`archiveBankProcessMaintenanceToDeleted`；`deleted_by` = session `login_id`，`deleted_at` = NOW()）
5. **`DELETE FROM bank_process_resend_daily_guard`**（受影响 `bank_process_id`；解除同日 Resend 锁）
6. **`DELETE FROM transactions`**（`deleteByIdsAndTenantId`）
7. 任一步失败整段回滚

不是主表加 `deleted_at` 的原地软删；主表物理删除以保证 Transaction Search / History / 余额不易误算。列表靠 §3 合并 `transactions_deleted` 展示划线行。

##### 与 Payment Maintenance 差异

| 项 | Payment | Bank Process |
|----|---------|--------------|
| 过滤 | `bank_process_posted_id IS NULL` | `bank_process_posted_id IS NOT NULL` |
| 类型 | PAYMENT / CLAIM / … / RATE | WIN / LOSE |
| 批次扩展 | `rate_group_id` | `bank_process_posted_id` |
| 额外清理 | `transactions_rate` | `bank_process_resend_daily_guard` |

#### 4.2 端点

`POST /api/maintenance/bankprocess-maintenance/delete`

#### 4.3 请求（`BankProcessMaintenanceDeleteRequest`）

```json
{
  "tenantId": 12,
  "transactionIds": [101, 102, 103]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | `> 0` |
| `transactionIds` | 是 | 活数据 `transactions.id`；前端去重且仅正整数 |

`deleted_by` **不由前端传**，取自 session `login_id`。

#### 4.4 响应

成功：

```json
{
  "success": true,
  "message": "BankProcess deleted successfully",
  "data": null
}
```

失败：`success: false`，`message` 为业务错误文案，例如：

- `Not logged in`
- `Read-only access cannot delete transactions`
- `Invalid tenant id`
- `Please select at least one record`
- `No matching bank process maintenance records to delete`
- `Failed to archive / delete bank process maintenance records`

#### 4.5 前端 Delete

- `buildSpringBankprocessMaintenanceDeleteRequest({ tenantId, transactionIds })`
- `deleteBankprocessData` → `POST api/maintenance/bankprocess-maintenance/delete`
- 页面用 `companyId` 作为 `tenantId`
- 已删行：`isBankprocessMaintenanceRowSelectable` 返回 false（不可再勾选）
- 删除后重新 list；Deleter 列显示 `{deletedBy} ({deletedAt})`，整行红色划线

---

### 5. 关键文件

| 层 | 路径 |
|----|------|
| Controller | `MaintenanceController.java` |
| Service | `MaintenanceService.java`，`MaintenanceServiceImpl.java` |
| DTO | `TransactionDTO.BankProcessMaintenanceRequest` / `DeleteRequest` / `BankProcessMaintenanceRow` |
| Dao / Mapper | `TransactionDao.java`，`TransactionMapper.xml`；`BankProcessResendDao.java`（guard 清理） |
| Frontend | `bankprocessMaintenanceLogic.js`，`BankprocessMaintenancePage.jsx`，`BankprocessVirtualDataRow.jsx` |

---

### 6. 变更检查清单

- [ ] List 是否仅 WIN/LOSE 且 `bank_process_posted_id IS NOT NULL`
- [ ] 是否 merge 归档行（软删划线 + Deleter 列）
- [ ] From 列是否在无 from account 时回退 `card_owner`
- [ ] 前端 batch 勾选是否仍用 `bankProcessId` + `periodType` + `transactionDate`
- [ ] Delete 是否扩展同 `bank_process_posted_id` 全部行
- [ ] Delete 是否清理 `bank_process_resend_daily_guard`
- [ ] 与 Payment 文档交叉引用是否一致

---

## 21. Bank Process Status 编辑锁定规则

> 原始独立文件：`docs/bankprocess-status-edit-lock.md`（内容已合并于此；原文件已改为跳转说明）


`OFFICIAL` / `E_INVOICE` / `BLOCK` 状态下的 Bank Process **禁止编辑**任何字段（day_start、day_end、frequency、contract、supplier/customer/company price、insurance、SOP、Remark、Profit Sharing 等）；仅 Status 本身仍可透过 Status 控件切换。`INACTIVE` 不受影响，维持可自由编辑。修改相关字段编辑入口时，**同步更新本文档**。

**锁点在「保存」而非「打开」**：用户仍可正常点击 Edit / Remark 图标打开对应弹窗，查看该 process 目前的完整信息（字段本身未做 disabled 处理）；真正的拦截发生在点击 Edit Process 弹窗的 **Update Process** / Remark 弹窗的 **Save** 时。

相关：[`accounting-due-frequency-rules.md`](./accounting-due-frequency-rules.md)（同三个状态对出账行为的影响）

### 范围

| 状态 | 可编辑 |
|------|--------|
| `ACTIVE` | 可以 |
| `INACTIVE` | 可以（不受本次改动影响） |
| `OFFICIAL` | 否 |
| `E_INVOICE` | 否 |
| `BLOCK` | 否 |
| `WAITING` | 未使用（后端不会手动设置此状态） |

Status 本身的切换（ACTIVE ↔ INACTIVE ↔ OFFICIAL ↔ E_INVOICE ↔ BLOCK）**不受此锁定影响**，走独立的 `POST /api/bank-process/update-status`，不经过下方的编辑守门。

### 后端实现

`BankProcessServiceImpl.java`：

- `EDIT_LOCKED_STATUS = {OFFICIAL, E_INVOICE, BLOCK}`
- `assertEditable(BankProcess existing)`：若 `existing.getStatus()` 在锁定集合内，抛出 `BusinessException`，不写入任何字段。
- 挂载点（守门放在拿到 `existing` 之后、写入前）：
  - 私有方法 `updateBankProcess`（被 `updateBankProcessDetails` / `POST /api/bank-process/update` 调用）——覆盖整包更新：day_start、day_end、frequency、contract、price（buy/sell/company）、insurance、SOP、Remark，以及同一事务内接着执行的 Profit Sharing 重建（`deleteBankProcessShareBatch` + `insertProfitSharing`）。因为整个方法在 `@Transactional` 内，`assertEditable` 一旦抛错，Profit Sharing 也不会被写入。
  - `updateBankProcessRemark`（`POST /api/bank-process/update-remark`）——列表上的快速备注编辑，是独立于主表单的 API 入口，需单独守门，否则可绕过主表单的锁定。

### 前端实现

`Count-frontend/src/pages/bankprocesslist/`：

- `hooks/useBankProcessListPage.js` 的 `openEdit(rowId)`：**不做拦截**，正常抓 row、拉账户列表、`setForm` / `setModalOpen(true)`，让用户看到该 process 的完整详情。
- `lib/bankProcessHelpers.js` 的 `bankProcessListRowToEditForm`：额外把 `row.issue_flag` 带进 `form.issue_flag`，供提交时判断。
- `hooks/useBankProcessListPage.js` 的 `submitForm`：在最前面（`guardWrite()` 之后）检查 `editMode && form.issue_flag ∈ {official, e_invoice, block}`，命中则 toast 提示并 `return`，不发起更新请求。
- `hooks/useBankProcessListPage.js` 的 `saveRemarkModal`：同样在发请求前检查 `remarkRow.issue_flag`，命中则 toast 提示并 `return`。
- `BankProcessListPage.jsx` 的 `openRemarkModal`：**不做拦截**，正常打开备注弹窗供查看。
- SOP 编辑是 Edit Process 弹窗内的子模态（`bankProcessTextModals.jsx`），没有独立 API，跟随主表单一起提交，由 `submitForm` 的检查挡住。

### 国际化 / 错误提示

`translateFile/pages/bankProcessTranslate.js`：

- 新增 key `errEditLockedByStatus`（en / zh 均有），供前端拦截时直接使用。
- `translateDynamicApiMessage` 新增正则匹配：消息含 `cannot be edited`（英文）或 `不可编辑`（中文）时，映射到 `errEditLockedByStatus`。用于万一前端检查被绕过、请求打到后端并被 `assertEditable` 拒绝时，toast 仍能显示正确翻译文案，而不是原始英文错误信息。

### 已知限制

- 后端守门以 Bank Process **当前**状态为准（请求发出时的 `existing.getStatus()`）。若用户打开 Edit Process 弹窗时状态本是可编辑的，但提交前另一个操作把该 process 状态改成锁定状态，前端 `form.issue_flag` 是打开当下的快照、不会跟着变，提交时前端检查可能放行，但后端 `assertEditable` 仍会正确拒绝（不会误放行），toast 会显示对应错误。
- 反之，弹窗打开时 `form.issue_flag` 已是锁定状态，前端检查会在用户点击 Update Process / Save 时先行拦截，不会发出请求；这是本次改动的主要行为。

---

## 22. Games Process List — Spring API 对齐说明

> 原始独立文件：`docs/process-list-spring-api.md`（内容已合并于此；原文件已改为跳转说明）


> **前端仓库**：`../Count-frontend/`  
> **后端前缀**：`/api/process/*`、 `/api/currency/*`、 `GET /auth/tenant-accessible`  
> **最后更新**：2026-07-27

---

### 1. 原则

- **前端对齐 Spring**，不要求后端恢复 PHP 字段（camelCase、`tenantId`、JSON RequestBody）。
- **Tenant 模型**：UI pill 的数字 `id` = `tenant.id`；列表/写操作 **RequestBody 传 `tenantId`**，**不出现在 URL query**。
- **API 层命名**：使用 `tenantId` / `fetchProcessListByTenantId` 等；**不在 API 模块使用 `scope` / `company_id` / `group_id` 参数**（Group/Company pill 仍用 `tenant-accessible` 做 UI 筛选，见 §5）。
- **Games 行**：列表 normalize 时过滤 `process.category === 'BANK'`（BANK 流程在 Bank Process List 页）。

---

### 2. Spring 端点一览

| 能力 | 方法 | 路径 | Body |
|------|------|------|------|
| 列表 | POST | `/api/process/process-list` | JSON 数字 `tenantId` |
| 表单 meta（币别） | POST | `/api/currency/list?tenant_id=` | — |
| Description 列表 | POST | `/api/process/list-description` | JSON 数字 `tenantId` |
| 新增 Description | POST | `/api/process/add-description` | `{ tenantId, name }` |
| 删除 Description | POST | `/api/process/delete-description` | `{ id, tenantId }` |
| 新增 Process | POST | `/api/process/add-process` | `ProcessDTO` 扁平字段 + `category: "GAME"` |
| 更新 Process | POST | `/api/process/update-process` | `{ id, tenantId, currencyId, descriptionIds[], dayOfWeeks[], … }` |
| 切换状态 | POST | `/api/process/update-status` | `{ id, tenantId }` |
| 删除 Process | POST | `/api/process/delete-process` | `{ id, tenantId }`（多选由前端循环） |
| Tenant 列表 | GET | `/auth/tenant-accessible?all=1` | — |
| 切换活动 tenant | POST | `/auth/switch-tenant?tenant_id=` | — |

成功响应：`success === true`（或 `status === "success"`）。

---

### 3. 列表：`ProcessDTO` → 表格行

Spring 返回结构化行（非 PHP 扁平 snake_case）：

```json
{
  "id": 15,
  "currencyCode": "MYR",
  "process": {
    "id": 15,
    "tenantId": 42,
    "category": "GAME",
    "code": "WM",
    "currencyId": 3,
    "removeWord": "TEST",
    "replaceWordFrom": "A",
    "replaceWordTo": "B",
    "remark": "",
    "status": "ACTIVE",
    "createdBy": "admin1",
    "updatedBy": "admin1",
    "createdAt": "2026-01-01T10:00:00",
    "updatedAt": "2026-01-02T11:00:00"
  },
  "processDescriptions": [{ "id": 1, "name": "FOOTBALL" }],
  "processDays": [{ "id": 10, "processId": 15, "dayOfWeek": 1 }]
}
```

前端 `normalizeProcessListItem()`（`processListHelpers.js`）映射：

| Spring | 表格/UI |
|--------|---------|
| `process.code` | `process_name` |
| `processDescriptions[]` | `description`（逗号拼接） |
| `processDays[].dayOfWeek` | `day_use`（`MON,TUE,…`） |
| `currencyCode` | `currency` |
| `process.status` | `status`（`ACTIVE` / `INACTIVE`，与 Spring enum 一致） |
| `category === 'BANK'` | 该行丢弃（Games 页不展示） |

**搜索 / Show Inactive / Show All**：Spring 列表暂无 query 参数；客户端 `applyProcessListFilters()`（`processRoutePrefetch.js` → `fetchGamesProcessListSlice`）。

---

### 4. Add / Update / Status / Delete

#### 4.1 新增（Add）

```json
{
  "tenantId": 42,
  "code": "WM",
  "category": "GAME",
  "currencyId": 3,
  "descriptionIds": [1, 2],
  "dayOfWeeks": [1, 4],
  "removeWord": "A,B",
  "replaceWordFrom": "X",
  "replaceWordTo": "Y",
  "remark": ""
}
```

- `dayOfWeeks`：`1=Mon … 7=Sun`（与 `PROCESS_WEEKDAY_OPTIONS` 一致）。
- **Multi-Process Add**：前端对每个 code 循环调用 `addProcess()`（不再走 PHP 批量 `selected_processes`）。
- **Copy From**：从当前列表行本地读取字段填充表单（不再调 PHP `copy_from`）。

#### 4.2 更新（Edit）

- **打开 Edit**：不调 `get_process`；用当前页 `rows` + `buildEditFormFromListRow()`。
- **提交**：`updateProcess(tenantId, { id, currencyId, descriptionIds, dayOfWeeks, … })`；`code` 只读不更新。

#### 4.3 状态 / 删除

- **Status 枚举**：Games Process **仅** `ACTIVE` | `INACTIVE`（`Process.Status`；DB `process.status` 同值）。**无 `WAITING`** — `WAITING` 属于 **Bank Process**（`BankProcess.Status`），不在本页使用。
- **展示**：表格 badge 显示 **Active** / **Inactive**（英文）；内部 state / 过滤 / API 对齐后端大写 enum。
- **切换**：点击 status badge → `updateProcessStatus(tenantId, processId)` → 服务端在 `ACTIVE` ↔ `INACTIVE` 间 toggle；读 `data.status`。
- **Delete**：仅 `INACTIVE` 可删；`deleteProcess(tenantId, id)` 逐条循环。

---

### 5. Tenant 选择与切换（UI 仍显示 Group / Company pill）

| 层 | 实现 |
|----|------|
| Pill 数据源 | `fetchOwnerCompaniesAll()` → `GET /auth/tenant-accessible` |
| 数字 id | `tenant.id`（内部 state 仍可能叫 `companyId`，语义为 tenant pk） |
| 切换 tenant | `syncCompanySessionApi(tenantId)` → `POST /auth/switch-tenant` |
| Games ↔ Bank 路由 | `resolveTenantIsBankOnly(tenantId, sessionMe)` + switch-tenant 返回的 `has_game` / `has_bank` |

**不在 API URL 或 RequestBody 中使用** `company_id` / `group_id` / `scope` / `permission=Games`。

---

### 6. 前端文件（2026-07-27）

| 文件 | 职责 |
|------|------|
| `pages/processlist/processListApi.js` | Spring 直调：list / description CRUD / add / update / status / delete / `fetchProcessFormMeta` |
| `pages/processlist/processListHelpers.js` | `normalizeProcessListItem`、`applyProcessListFilters`、`buildEditFormFromListRow`、`PROCESS_WEEKDAY_OPTIONS` |
| `pages/processlist/processRoutePrefetch.js` | `fetchGamesProcessListSlice` → Spring list + 客户端过滤 |
| `pages/processlist/ProcessListPage.jsx` | 页面编排；mutations 全 Spring |
| `pages/processlist/components/ProcessFormModal.jsx` | 表单 UI；`scopeTenantId` prop（传给 RemoveWordChipInput 作 tenant 隔离） |

---

### 7. 已移除的 PHP 调用

| 旧 PHP | 替代 |
|--------|------|
| `processlist_api.php?action=list` | `POST /api/process/process-list` |
| `addprocess_api.php`（form meta） | `fetchProcessFormMeta` + `fetchProcessListByTenantId`（existingProcesses 来自列表行） |
| `addprocess_api.php`（add / description） | `addProcess` / `addProcessDescription` |
| `processlist_api.php?action=get_process` | 列表行本地回填 |
| `processlist_api.php?action=update_process` | `updateProcess` |
| `toggle_process_status_api.php` | `updateProcessStatus` |
| `delete_processes_api.php` | 循环 `deleteProcess` |
| `addprocess_api.php?action=copy_from` | `buildCopyFromFormPatchFromRow` |

---

### 8. 本地验证清单

1. 打开 `/process-list`，Network 可见 `POST /api/process/process-list`，body 为数字 tenant id。
2. Add：提交后 `POST /api/process/add-process`，body 含 `tenantId`、`code`、`category:"GAME"`。
3. Edit：打开时不应出现 `get_process`；保存走 `update-process`。
4. 点 Status：`POST /api/process/update-status`。
5. 删除 Inactive：`POST /api/process/delete-process`（每条一次）。
6. Description 弹窗：`list-description` / `add-description` / `delete-description`。
7. 切换 pill：`POST /auth/switch-tenant?tenant_id=`。

---

### 9. 维护约定

- 新增 Process 相关 Spring 字段时：**先改后端 DTO + 本文**，再改 `normalizeProcessListItem` 与表单映射。
- 与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md) 第 9 节、`datacapture-spring-api.md` 的 tenant 约定保持一致。
- 服务端 list 若将来支持 search/status query，可删除客户端 `applyProcessListFilters` 中对应逻辑。

---

### 10. Process.Status 与 Bank Process 的区别

| 模块 | Java enum | DB | Games Process List 页 |
|------|-----------|-----|------------------------|
| **Games Process** | `Process.Status` → `ACTIVE`, `INACTIVE` | `process.status` ENUM | ✅ 使用；badge 可点击 toggle |
| **Bank Process** | `BankProcess.Status` → `WAITING`, `ACTIVE`, `OFFICIAL`, … | `bank_process.status` | ❌ 不在本页；见 Bank Process List |

历史 PHP `process` 表曾用小写 `active`/`inactive`；迁移后须与 Spring 一致（见 `sql/migrate_enums_to_uppercase.sql`）。前端 **不要** 为 Games Process 引入 `WAITING` 分支或 `status-waiting` badge。

---

## 23. Account 多公司归属 (Account ↔ Company Multi-Tenant)

> 原始独立文件：`docs/account-company-multi-tenant.md`（内容已合并于此；原文件已改为跳转说明）


### 背景

Account Edit/Add 弹窗的「Choose companies」原本用 checkbox 呈现成多选，但实际上完全没接上：

1. **候选清单混入 Group**：清单数据来自 `GET /auth/tenant-accessible`，Group 和 Company
   用同一种形状返回，前端从未过滤 `tenant_type === "GROUP"`，导致 Group（如 `OK`）会跟真正的
   Company（`OK1`/`OK2`）一起出现在勾选框里。
2. **勾选结果没有送到后端**：`selectedCompanyIds` 只是本地 UI state，仅用来渲染摘要文字；
   `saveForm()` 实际只送出单一 `scopeTenantId`（账号原本所属的那个 tenant），后端
   `UserServiceImpl.updateUser` 也只处理这一个值，`UserListDTO.tenantIds` 字段虽然存在
   （注释写着 `company.id in frontend picker`）但从未被写入或读取。

数据库层面其实已经是为多对多设计的：`account_tenant_access` 的唯一键是
`(account_id, tenant_id)`（不是每 account 一行）、`account_currency` 本来就是按
`(account_id, tenant_id, currency_id)` 独立存储、`account` 表也有注释明确写着
"account_id is unique per tenant, not globally"。`UserDao` 甚至已经有一个从未被调用过的
`findTenantIdsByUserId(id)`。本次改动是把这个已经设计好、只是没接完的功能补齐，
让一个 Account 真正可以同时属于多间 Company，而不是新发明一套模型。

修改本文档描述的任何写入路径时，**同步更新本文档**。

### 资料模型

| 表 | 归属范围 | 说明 |
|---|---|---|
| `account` | 全局唯一一行 | `name` / `role` / `password` / `status` / `payment_alert` / `remark` 等字段是**跨所有归属公司共用**的，不是每公司各自一份 |
| `account_tenant_access` | `(account_id, tenant_id)` 唯一 | 一个 account 现在可以对应多笔，代表它同时归属多间 Company |
| `account_currency` | `(account_id, tenant_id, currency_id)` 唯一 | 币种设定仍然是**每间公司各自独立**；新加入一间公司不会自动带入其他公司已设定的币种 |

### 后端实现

`UserServiceImpl.java`：

- `normalizeTenantIds(List<Integer>)`：去重 + 丢弃非正数。
- `assertCompanyTenants(List<Integer>)`：逐一用（既有但之前只在别处用到的）`TenantDao.findTenantById`
  校验每个 id 存在且 `tenantType == COMPANY`，拒绝 Group id 混进来——即使前端的过滤又失效，
  后端这层还是会挡住。
- `assertAccountCodeAvailable(tenantId, accountCode, excludeAccountId)`：复用既有的
  `findAccountIdByTenantIdAndCode`，确认该公司底下这个 account 代码没有被**另一个不同的** account 占用。
- `createUser`：从 `userListDTO.getTenantIds()` 取目标公司集合（为空时退回 `[scopeTenantId]`
  向后兼容），校验后对每个 tenant 各插入一笔 `account_tenant_access`；币种仍然只写入
  `scopeTenantId` 这一间公司。
- `updateUser`：取得目前已有的 tenant 集合（`findTenantIdsByUserId`）跟前端送来的目标集合做 diff：
  - `toAdd`：逐一做代码唯一性检查后 `insertAccountTenantAccess`。
  - `toRemove`：`deleteUserTenantAccessByAccountIdAndTenantId` + 同步清掉该公司的
    `account_currency`（避免留下指向已脱离公司的孤儿币种设定）。
  - 若 `scopeTenantId` 本身被从集合里移除（用户在编辑当下所在的那间公司把自己勾掉）：
    结果 DTO 改用集合里剩下的任一 tenant 重新查询，币种同步这一步也会跳过（该公司已经不再关联）。
  - 移除了旧的 `updateAccountTenantAccess`（`SET x=x WHERE x=x`，本质是 no-op）调用，
    以及 `UserDao` / `AccountMapper.xml` 里对应的方法定义——完全被上面的 add/remove 同步取代。
  - 全程仍在同一个 `@Transactional` 里，中途任何校验失败都会整体回滚，不会有写一半的情况。
- `findUserByTenantId`：对返回的每一行额外查一次 `findTenantIdsByUserId`，把完整的
  `tenantIds` 塞进 DTO，让前端能够知道这个 account 实际归属的**全部**公司（而不只是当前
  正在浏览的这一间）。这里是 N+1 查询，但这是内部管理后台、单一公司帐号数量不大，
  不值得为此换成批次查询。
- `deleteUserByIdAndStatus`：先删该公司的 `account_tenant_access` 与对应 `account_currency`，
  然后检查 `findTenantIdsByUserId` 是否还有剩——**只有完全没有其他公司归属时才会真的硬删除
  `account` 这一行**。这是顺手修的一个潜在 bug：`account_tenant_access` 对 `account`
  没有数据库层级的外键约束，旧逻辑不管有没有其他公司归属都会直接删掉 `account`，
  会让该账号在其他公司里的关联行悄悄变成孤儿、永久性地在那些公司里消失。

### 前端实现

`Count-frontend/src/pages/account/`：

- `AccountListPage.jsx` 的 `allCompanyButtons`：过滤条件加上
  `tenant_type !== "GROUP"`（原本只过滤 `isVirtualGroupLinkCompanyRow`）——这才是真正堵住
  Group 出现在勾选框里的地方。`groupOnlyAccountMode`（另一套「选 Group」的单选模式）不受影响。
- `accountListApi.js`：
  - `normalizeAccountListItem` 把后端的 `tenantIds` 映射成 `tenant_ids`。
  - 新增 `normalizeAccountTenantIds`（跟既有 `normalizeAccountCurrencyIds` 同款：去重 + 丢非正数）。
  - `buildAccountCreateRequest` / `buildAccountUpdateRequest` 新增 `tenantIds` 参数并放进请求体。
  - 新增 `tenantIdsToPickerCompanyIds`（既有 `tenantIdToPickerCompanyIds` 的阵列版本），
    用来把一个 account 完整的公司集合转成勾选框可以直接使用的 id 列表。
- `AccountListPage.jsx` 的 `loadSelectionMeta` / `openEdit`：`openEdit` 已经有本地的
  `row` 可用，改成把它传进 `loadSelectionMeta({ editingRow: row })`，让 Edit 模式下
  `selectedCompanyIds` 用该 account **完整**的 `tenant_ids` 预先勾选，而不是只勾当前
  浏览的那一间。Add 模式默认只预选当前公司，行为不变。
- `saveForm()`：从 `selectedCompanyIds` 组出 `tenantIds`（数字化 + 去重），非 Group-only
  模式下若为空会挡下并提示（复用既有的 `pleaseSelectCompanyFirst`），并把 `tenantIds`
  传进 `buildAccountCreateRequest` / `buildAccountUpdateRequest`。

**防止「顺手截断」多公司账号**：后端在 `tenantIds` 为空时会退回 `[scopeTenantId]`
向后兼容——这代表任何**没有主动带上完整 `tenantIds`** 的更新请求，都会把一个多公司账号
悄悄裁成只剩当前这一间公司。因此下列既有的、不是走 `saveForm()` 的更新入口也一并补上：

- `accountListApi.js` 的 `toggleAccountUserPaymentAlert`（列表上快速切换提醒开关）：
  改成带上 `row.tenant_ids`。
- `AccountListPage.jsx` 里强制解除币种链接的那段（`unlinkCurrentAccountFromCurrency`）：
  改成带上当下 `selectedCompanyIds`。
- `bankprocesslist/hooks/useBankProcessListPage.js`：这个页面自己有一套「新增/编辑
  Account」的小弹窗（`submitAccountModal` / `loadAccountModalSelectionMeta` /
  `refreshAccountModalCurrenciesIfOpen`），原本的注释写着 "Spring account create/update
  is scoped to one tenant"、每次都只塞一个 tenant id。这次一并改成：编辑时改用该
  account 的完整 `tenant_ids` 预填 `accountModalSelectedCompanyIds`，提交时把它当作
  `tenantIds` 传出去；找不到既有集合才退回单一 `scopeTenantId`。这个页面本身没有
  开放多选 UI（依然是单一入口），这里只是确保**编辑一个已经属于多间公司的账号时不会
  意外把它裁掉**，不是在这个页面新增多选功能。

### 已知限制

- `status` / `payment_alert` / `remark` 等字段在 `account` 表上只有一份，是所有归属公司
  **共用**的——把一个跨公司共用账号在某间公司停用，会影响它在所有公司的状态显示。
  这是既有 schema 设计（不是本次改动引入的），本次没有改变这个行为。
- 币种设定仍然是每间公司各自独立；新增一间公司后需要另外去该公司的 Edit 里设定币种。
- `findUserByTenantId` 每行多一次 `findTenantIdsByUserId` 查询（N+1），在这个体量下可接受。

### 相关清理：移除 `UPLINE` 兼容分支

顺带处理：`UserServiceImpl.normalizeAccountLedgerRole` 原本有
`if ("UPLINE".equals(normalized)) normalized = "SUPPLIER";` 这段历史兼容代码
（对应 `backend/src/main/resources/sql/migrate_upline_role_to_supplier.sql` 那次数据迁移）。
改动前用 `SELECT UPPER(TRIM(role)), COUNT(*) FROM account GROUP BY 1` 确认线上数据库
已经没有任何 `UPLINE` 残留，才移除这个分支。`PARTHER` → `PARTNER` 的兼容分支跟这次无关，保留不动。

前端检查过没有任何 account role 相关的 `UPLINE` 引用；仓库里唯一匹配到 "Upline" 的地方
是 `datacapture/paste/*` 底下解析外部投注平台报表列名（例如 "Upline Member Bonus"）的逻辑，
是完全不同领域的概念，未做改动。

---

## 24. Transaction list filters — Show Payment / Show Win/Loss / Show all 0 balance

> 原始独立文件：`docs/transaction-list-payment-winloss-filters.md`（内容已合并于此；原文件已改为跳转说明）


> Win/Loss 数据源（Bank Process 记账 + Data Capture Summary Submit + 手动 Adjustment/Profit/Rate-middleman）的聚合实现见 [transaction-datacapture-winloss.md](./transaction-datacapture-winloss.md)。

勾选筛选时的展示规则。修改筛选时同步更新本文档。

### 共同原则

| 项 | 约定 |
|----|------|
| 日期范围 | Transaction 页当前搜索 **From–To** |
| 有对应流水就展示 | 净额非 0，**或** API period 标志有流水（含正负轧成 0） |
| balance = 0（有流水） | 只要本期有对应列动账 → **仍展示**（算「有流水」类，不是「从未动账」） |
| 从未动账 | 该账户×币种 **没有任何 APPROVED 交易历史** → 仅在勾选 **Show all 0 balance** 时补进列表 |

### Show Payment Only

- 看 **Cr/Dr**：`PAYMENT` / `CLAIM` / `CLEAR` / `CONTRA` / RATE 转账腿、Domain Payment 等
- 判定：`cr_dr` 非 0，或 `hasCrDrInPeriod` → `has_crdr_transactions`

### Show Win/Loss Only

- 看 **Win/Loss**：Bank Process `WIN`/`LOSE`、**Data Capture Summary Submit `WIN`/`LOSE`**、`ADJUSTMENT`、`PROFIT`、RATE Middle-Man 等（详见 [transaction-datacapture-winloss.md](./transaction-datacapture-winloss.md)）
- 判定：`win_loss` / `win_loss_full` 非 0，或 `hasWinLossInPeriod` → `has_win_loss_transactions`
- **不**用 `has_period_id_product_rows`（避免 Payment-only 误入）

### Show all 0 balance

勾选后后端 `showAllZeroBalance=true`，Search 额外返回 **neverTransacted** 壳行（账户×已关联币种、无任何 APPROVED 流水）。

#### 单独勾选

展示 = 原有 Search 活动行（含有流水但 balance=0）**∪** 从未动账壳行。

#### + Show Win/Loss

展示 = 有 W/L 流水/金额的账户 **∪** 从未动账账户。

#### + Show Payment

展示 = 有 Cr/Dr 流水/金额的账户 **∪** 从未动账账户。

#### + Show Payment + Show Win/Loss

展示 =（有 W/L **或** 有 Cr/Dr）**∪** 从未动账账户（活动条件为 **OR**）。

### 实现

| 层 | 位置 |
|----|------|
| 后端壳行 | `TransactionDao.findAccountCurrencyShells` + `TransactionSearchServiceImpl` |
| 后端标志 | `SearchRow.neverTransacted` |
| 前端请求 | `buildSpringSearchRequest({ showAllZeroBalance: !hideZeroBalance })` |
| 前端判定 | `rowHasPeriodCrdr` / `rowHasPeriodWinLoss` / `rowIsNeverTransacted` |
| 前端应用 | `filterTransactionTableRows` → `useTransactionSearch.tablePresentation` |

---

## 25. Transaction Payment / Payment History — Data Capture Win/Loss 补聚合

> 原始独立文件：`docs/transaction-datacapture-winloss.md`（内容已合并于此；原文件已改为跳转说明）


> **相关**：[datacapture-spring-api.md](./datacapture-spring-api.md) §2.8（Summary 最终 Submit 写 `transactions`）、[transaction-list-payment-winloss-filters.md](./transaction-list-payment-winloss-filters.md)（Show Win/Loss Only 等筛选）
> **最后更新**：2026-08-11

---

### 1. 问题

Data Capture Summary Submit（GAME）成功后，`data_captures` / `data_capture_line` / `process_submitted` / `transactions` 四张表都正确写入了数据（见 `datacapture-spring-api.md` §2.8），但 Transaction Payment 主列表、右上 Payment History 明细都看不到这几笔——跟公司/日期/币别筛选无关，换任何条件都查不到。

**根因：** `TransactionMapper.xml` 里所有跟 WIN/LOSE 相关的聚合查询（`aggregateBankProcessWinLoss`、`aggregateBankProcessBfByAccount`、`findBankProcessHistoryLines`）都写死 `t.bank_process_posted_id IS NOT NULL`——这个前提假设"WIN/LOSE 只会来自 Bank Process 记账流程"（`AccountingDueServiceImpl.insertTxnLine()` 才会 set 这个字段）。但 `DataCaptureSummaryServiceImpl.toTransaction()`（Data Capture Summary Submit）也会写 `transaction_type IN ('WIN','LOSE')`，却从来不 set `bankProcessPostedId`，插入后这一列是 `NULL`——落进了任何查询分支都覆盖不到的空档：manual 那几条（ADJUSTMENT/PROFIT/RATE middleman）虽然也是 `bank_process_posted_id IS NULL`，但过滤的 `transaction_type` 不是 WIN/LOSE，接不住。

排查时确认过整个后端只有两处会写 `transaction_type IN ('WIN','LOSE')`：

| 来源 | `bank_process_posted_id` |
|------|---------------------------|
| `AccountingDueServiceImpl.insertTxnLine()`（Bank Process 记账 posting 流程） | 总是非空 |
| `DataCaptureSummaryServiceImpl.toTransaction()`（Data Capture Summary Submit） | 总是 `NULL` |

所以按 `bank_process_posted_id IS NULL AND transaction_type IN ('WIN','LOSE')` 切分，精确对应 Data Capture 来源，不会漏、也不会跟其他来源混。

---

### 2. 修复：新增对称聚合查询

不改现有查询的 `IS NOT NULL` 条件（避免影响 Bank Process 记账原有行为），而是照抄一份、把条件反过来，各自独立成一条 DAO/mapper 方法，最后在 service 层合并。

#### 2.1 Transaction Payment 主列表（`TransactionSearchServiceImpl`）

| 新增 | 镜像自 | 差异 |
|------|--------|------|
| `TransactionDao.aggregateDataCaptureWinLoss` | `aggregateBankProcessWinLoss` | 仅 `bank_process_posted_id IS NOT NULL` → `IS NULL`，其余 SQL（bf/period 分段求和、WIN 正 LOSE 负、currency/role 过滤）完全一致 |

`TransactionSearchServiceImpl.buildWinLossSearchSlice()`（原 `buildBankProcessSearchSlice`，改名见 §3）在原有 bank/adjustment/profit/rate-middleman 四路之外，多查一路 `aggregateDataCaptureWinLoss`，用同一套 `mergeWinLossAggregateRows`（按 `accountDbId + currencyCode` 合并求和）拼进结果。

#### 2.2 Payment History 明细（`TransactionHistoryServiceImpl`）

| 新增 | 镜像自 | 差异 |
|------|--------|------|
| `TransactionDao.aggregateDataCaptureBfByAccount` | `aggregateBankProcessBfByAccount` | 同上，只反转 NULL 判断 |
| `TransactionDao.findDataCaptureHistoryLines` | `findBankProcessHistoryLines` | 反转 NULL 判断；**去掉** `bank_process_accounting_posted`/`bank_process` join（Data Capture 这批数据没经过 Bank Process 记账，硬 join 查不到东西），`cardOwner` 直接给 `NULL`；`bankProcessLine` 仍给 `TRUE`（见 §4） |

`TransactionHistoryServiceImpl.buildWinLossHistorySlice()`（原 `buildBankProcessHistorySlice`）把这两个新查询的结果并进 BF map 和明细行列表。

---

### 3. 顺带做的命名重构（不改逻辑，纯改名）

用户反馈 `buildBankProcessSearchSlice` 这个名字容易误导——方法名看起来只处理 Bank Process，实际上里面还揉了 ADJUSTMENT / PROFIT / RATE middleman，现在又加了 Data Capture。两个 service 文件里同样的命名问题一并理顺：

| 文件 | 原名 | 新名 |
|------|------|------|
| `TransactionSearchServiceImpl` | `buildBankProcessSearchSlice` | `buildWinLossSearchSlice` |
| | 局部变量 `bank` | `winLoss` |
| | `applyBankAggregates` | `applyWinLossAggregates` |
| | `MergedAccount.fromBank` | `MergedAccount.fromWinLoss` |
| | `SearchSlice` record 字段 `bankProcess` | `isWinLossSource` |
| `TransactionHistoryServiceImpl` | `buildBankProcessHistorySlice` | `buildWinLossHistorySlice` |
| | 局部变量/参数 `bank` | `winLoss` |

两个类的 Javadoc 头注释也同步改成"Win/Loss（Bank Process + Data Capture + 手动 Adjustment/Profit/Rate-middleman）与 Domain Payment (Cr/Dr) 分别构建"。

**没动的**：`HistoryLineRow.bankProcessLine` 字段名，以及它经 `toHistoryRow()` 映射出去的 `HistoryRow.isBankProcessTransaction` ——后者是暴露给前端的 API 字段，改名涉及前端契约，这次不动。它现在的真实含义已经变成"按 Win/Loss 处理（摆 Win/Loss 列而不是 Cr/Dr 列）"，不再字面等于"来自 Bank Process"；`findDataCaptureHistoryLines` 也把它设成 `TRUE` 以获得同样的显示分支。

---

### 4. Payment History "ID PRODUCT" 列显示 "DATA CAPTURE"

`bankProcessLine=TRUE` 现在真正 Bank Process 记账行和 Data Capture 行都会用到（为了让金额落在 Win/Loss 列），单靠它已经分不出来源。所以新增一个专门字段区分：

| 新增 | 说明 |
|------|------|
| `TransactionDTO.HistoryLineRow.dataCaptureLine` | 仅 `findDataCaptureHistoryLines` 的 SELECT 里给 `TRUE AS dataCaptureLine`；`findBankProcessHistoryLines` 不选这列，MyBatis 留空即为 `null`/false |

`TransactionHistoryServiceImpl.toHistoryRow()` 的 product 分支新增判断（插在 RATE 和「非 Bank 时走 Domain product」之间）：

```java
} else if (isDataCapture) {
    row.setProduct("DATA CAPTURE");
} else if (!isBank) {
    row.setProduct(resolveDomainHistoryProduct(line));
}
```

真正 Bank Process 记账行（`isBank=true` 且 `isDataCapture=false`）继续保持 product 空白，不受影响。

#### 4.1 前端也要改（这里当初判断错了）

原以为前端不用改——`transactionHistoryNormalize.js` 确实已经在读 `raw.product` 并透传成 `product` 字段。但渲染 ID PRODUCT 列的两处都有一层前端自己的取值逻辑，只要 `is_bank_process_transaction`（= 后端 `bankProcessLine`）为 `true` 就优先显示 `card_owner`，根本不看 `product`：

- `TransactionHistoryTable.jsx`（Payment History 表格本体）
- `paymentHistoryMemberReportExport.js`（Payment History 报表导出）

因为 §3 提到 `bankProcessLine`/`isBankProcessTransaction` 现在真正 Bank Process 行和 Data Capture 行都是 `true`，而 Data Capture 行没有 `card_owner`（`NULL`），这两处逻辑会把它 fallback 成 `"-"`，`product = "DATA CAPTURE"` 完全被无视——这就是当时排查到的现象（Win/Loss 金额和余额都对，唯独 ID PRODUCT 空白）。

修复方式：两处都改成优先用 `product`（真正 Bank Process 记账行从不 set 这个字段，所以不受影响），没有才 fallback 到 `card_owner`：

```js
const idProductDisplay = r.product || (r.is_bank_process_transaction ? r.card_owner : "") || "-";
```

**结论**：以后再新增一个会复用 `bankProcessLine=TRUE`（走 Win/Loss 显示分支）的来源，除了本文 §4 的后端 `product` 字段，还必须检查前端有没有类似"按 is_bank_process_transaction 二选一"的取值逻辑——不能假设前端只读 `product` 就完事。

---

### 5. 自测

1. Games process Submit（`datacapture-spring-api.md` §2.8 自测 3）→ 打开 Transaction Payment，该账户所在币别行的 Win/Loss 金额包含这笔（不再是 0/缺失）。
2. 打开该账户 Payment History → 出现对应行，`WIN/LOSE` 列有正确签名金额，`DESCRIPTION` 是 `"{processCode}: {formula}"`（如 `BONUS: 3000`），**`ID PRODUCT` 列显示 `DATA CAPTURE`**（不是空白）。
3. Bank Process 记账（`AccountingDueServiceImpl` 走的那条）产生的 WIN/LOSE 行，Payment History 里 `ID PRODUCT` 仍保持空白——确认没被 Data Capture 分支误伤。
4. `Show Win/Loss Only` 勾选后该账户仍会出现（判定逻辑本身没变，只是数据源多了一路，见 `transaction-list-payment-winloss-filters.md`）。

---

### 6. 维护约定

- 以后再新增一个会写 `transactions` 且 `transaction_type IN ('WIN','LOSE')` 的来源（不经过 Bank Process posting），必须同时检查 `TransactionMapper.xml` 三条 WIN/LOSE 查询（`aggregateBankProcessWinLoss`/`aggregateBankProcessBfByAccount`/`findBankProcessHistoryLines`）能不能覆盖到；覆盖不到就照本文 §2 的模式再镜像一份，不要直接改现有 Bank Process 查询的 `IS NOT NULL` 条件。
- 新来源如果需要在 Payment History `ID PRODUCT` 列有专属标签，参照 §4 加一个独立的 `xxxLine` 布尔字段，不要复用 `bankProcessLine`（它已经是"走 Win/Loss 显示分支"的通用开关，不代表来源）。
- 后端给了 `product` 字段不代表前端会自动显示——参照 §4.1，务必确认前端渲染 ID PRODUCT 的地方不是只按 `is_bank_process_transaction` 二选一（`card_owner` vs `product`），而是 `product` 优先。

---

## 26. RATE Middle-Man / Rate-Mul / Platform Fee（Spring Boot 现行实现）

> **本节与独立文件 [`transaction-rate-middleman-logic.md`](./transaction-rate-middleman-logic.md) 内容同步维护**——RATE 逻辑相对复杂，拆成单独文件方便查阅，这里保留一份完整拷贝方便跟其他迁移章节一起看。**改这块逻辑时，两份文件都要同步更新**，避免内容分叉。

> 范围：`POST /api/transaction/submit`（`transactionType=RATE`）在 Spring Boot 后端的完整实现。
> 与 `Count-frontend` 的 payload 映射见 [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md)。
>
> 本文档描述的是**当前仓库（Spring Boot）**的简化模型，**不是** legacy PHP（`transaction_entry` / `transactions_rate_details`）那一套。两边字段命名相似但语义不同，改代码或读旧参考文档时不要混用。

---

### 1. 模型概述

RATE 是「两条 Cr/Dr 腿 + 可选 Middle-Man Win/Loss 腿」的组合，一次提交落库：

1. **leg1**：第一币种，`leg1ToAccountId` / `leg1FromAccountId` 两个账户之间的一笔 Cr/Dr，金额 = `leg1Amount`；
2. **leg2**：第二币种，`leg2ToAccountId` / `leg2FromAccountId` 之间的一笔 Cr/Dr，金额 = flat 毛额 `grossTo`（**不是** `leg2Amount`，见下方 2026-08 变更）；
3. **Middle-Man（可选）**：账户 + Rate-Mul 乘数/除数 和/或 Fee 和/或 Platform Fee 的任意组合，产生 0～3 笔 Win/Loss 分录（Rate / Fee / Platform Fee 各一笔）；
4. **`transactions_rate`**：一行头表，记录 FX 元数据（汇率、双边币种/金额、Middle-Man 原始输入），用 `rate_group_id` 把 leg1/leg2 串起来。

RATE 直接落 `APPROVED`，不走待审批。

**leg1 与 leg2 都是必填**——这是跟 PHP legacy 模型最大的差异：PHP 那边"第二组账户"是可选的（不填就只有 leg1 一笔账，`transactions_rate` 里的第二币种信息纯展示）；Spring Boot 这边 `transactions_rate.leg2_transaction_id` 是 `NOT NULL` 外键，`submitRate()` 对 leg1/leg2 都无条件调用 `requireFromToAccounts(...)`。前端已经在 `useTransactionForm.js` 里把"第二组账户"改成强制必填以对齐这个约束（详见前端文档）。

leg2 是**单一对称金额**（一个 `amount` 字段，Cr/Dr 双边共用），不支持 PHP 那种"Transfer To 侧和 From 侧金额不同"的写法——这也是简化点之一。

**2026-08 记账方式变更**：leg2 现在**恒等于 `amountFrom × exchangeRate` 的 flat 毛额**，服务端自己算，不再信任前端传来的 `leg2Amount`（该字段仍会被接收但不参与记账/校验，纯前端展示用）。Rate-Mul、Fee、Platform Fee 造成的所有扣减，一律通过**额外插入**的 Win/Loss 分录去冲抵 `leg2.fromAccountId()`（"from account"），不会改动 leg2 本身的金额——这样 leg2 的 To 账户永远拿到干净的毛额，From 账户的净额则由明细分录累加得出，审计明细不会被"存净额"吞掉。详见第 6、7、10、11 节。

---

### 2. 入口与主要类

| 层级 | 文件 | 职责 |
|------|------|------|
| Controller | `controller/TransactionController.java` | `POST /api/transaction/submit` |
| DTO | `dto/TransactionSubmitDTO.java` | 请求/响应共用一个 DTO |
| Service（提交） | `service/impl/TransactionSubmitServiceImpl.java` | `submitRate()` / `resolveMiddleman()` 等 |
| Service（Payment History） | `service/impl/TransactionHistoryServiceImpl.java` | `mergeRateMiddlemanDeductionsIntoMainLeg()` 等，见第 10 节 |
| Service（CONTRA 汇总） | `service/impl/TransactionSearchServiceImpl.java` | `buildDomainPaymentSearchSlice()` 等，见第 11 节 |
| Rate-Mul 算法 | `util/RateMulCalculator.java` | 解析 Rate-Mul 输入、算佣金 |
| 金额精度 | `util/TransactionMoneyFormat.java` | 见 [`transaction-amount-precision.md`](./transaction-amount-precision.md) |
| Entity | `entity/TransactionRate.java` | 映射 `transactions_rate` |
| DAO | `dao/TransactionRateDao.java` + `mybatis/TransactionRateMapper.xml` | 头表 insert/delete |
| Schema | `sql/schema.sql`（`transactions_rate` 定义）+ `sql/migrate_rate_platform_fee_and_ratemul.sql`（增量迁移） | |

---

### 3. `TransactionSubmitDTO` 的 RATE 相关字段

```text
leg1ToAccountId / leg1FromAccountId / leg1CurrencyId / leg1CurrencyCode / leg1Amount
leg2ToAccountId / leg2FromAccountId / leg2CurrencyId / leg2CurrencyCode / leg2Amount
exchangeRate            数值化汇率（正数，≤8 位小数）
rateExpression           FX 原始文本，如 "/1.5"、"3.15" —— 供 Rate-Mul 判断 FX 是除法还是乘法写法用

middlemanAccountId
middlemanRate            legacy 裸乘数字段（BigDecimal），仅在 middlemanRateExpression 缺省时兜底
middlemanRateExpression  Rate-Mul 原始文本，如 "/1.55"（除法模式）或 "2.93"（乘法模式/新汇率）
middlemanAmount          Service Fee 面值，第二（leg2）币种，不换汇
platformFeeAmount        Platform Fee 面值，第二（leg2）币种，恒正数（用法见第 6、7 节）
```

三个 Middle-Man 输入项（Rate-Mul / Fee / Platform Fee）**互相独立、任意子集都可以单独存在**——账户是否必填只取决于三者是否有任一被填写。

---

### 4. `resolveMiddleman()` 决策树

1. 三项都没填 → 无 Middle-Man，返回 `null`；leg2 照样记 `grossTo`（`leg1Amount × exchangeRate`），跟有没有 Middle-Man 无关（见第 7 点，`validateRateAmounts` 已删除）。
2. 选了账户但三项都没填 → 报错「Middle-Man requires rate multiplier, fee, and/or platform fee」。
3. 填了任一项但没选账户 → 报错「Middle-Man account is required when rate multiplier, fee, or platform fee is set」。
4. 否则：
   - **Rate-Mul**：`RateMulCalculator.parseMiddlemanRateInput()` 解析 `middlemanRateExpression`（缺省时用 `middlemanRate` 的字符串形式兜底），解析出的除数/乘数还要过 `TransactionMoneyFormat.requireMaxScale(..., 8)`（跟其他 RATE 数值字段同一条规则）。再调 `RateMulCalculator.computeCommission(...)` 算出佣金，**可能为负**（中间人倒贴）。
   - **Fee**：`middlemanAmount` 走 `parsePositiveRateAmount`（必须 >0，≤8 位小数），**不再乘汇率**——直接就是 leg2 币种的面值。
   - **Platform Fee**：`platformFeeAmount` 同样必须 >0，≤8 位小数。
   - `feeNet = Fee − PlatformFee`（可能 ≤0）。
5. **只有 >0 的部分才会真正插入分录**：`ratePortion = rateMulCommission > 0 ? rateMulCommission : null`；`feePortion = feeNet > 0 ? feeNet : null`。倒贴（Rate-Mul 为负）或 PT 把 Fee 吃光（feeNet ≤0）**都不报错**，只是那一笔 Win/Loss 分录不写；`transactions_rate` 头表仍然记录用户的原始输入供审计。
6. `total = (ratePortion 或 0) + (feePortion 或 0)` 必须 `< grossTo`，否则报错「Middle-Man total must be less than leg2 gross amount」。
7. ~~有 Middle-Man 时校验 `leg2Amount = grossTo − total`~~——**2026-08 起已移除**。leg2 现在恒记 `grossTo`（服务端算，不看前端传的 `leg2Amount`），不存在"净额是否对得上"这个校验了，`RATE_AMOUNT_TOLERANCE` 常量、`validateRateAmounts()` 方法也一并删除。

---

### 5. Rate-Mul 算法（`RateMulCalculator`）

三种分支，跟前端 `transactionSubmitHelpers.js` 的 `computeRateMulCommission` 完全对齐：

| Rate-Mul 输入 | FX Rate 写法 | 公式 |
|---|---|---|
| `/newDivisor`（除法） | 必须也是 `/divisor` 才生效 | `from/newDivisor − from/divisor` |
| 纯正数（乘法/点数） | FX 是 `/divisor` | 点数直接用：`mul × 1000` |
| 纯正数（乘法/新汇率） | FX 是普通乘法写法 | 带符号做差：`(原汇率 − mul) × fromAmount`（结果可为负，即倒贴） |
| 模式与 FX 写法不匹配 | — | 佣金 = 0（忽略，不报错） |

纯负数输入一律无效（`ParsedRate.valid = false`），提交时报错。中间计算用 20 位小数精度（`RoundingMode.HALF_UP`），最终结果统一交给 `TransactionMoneyFormat.normalizeComputedRate`（超过 8 位才 HALF_UP 到 8 位）。

**已知限制**：DIVIDE 模式和"FX 是除法写法时的点数模式"都需要 `rateExpression`（FX 原始文本）才能判断 FX 是不是 `/divisor` 写法——如果前端没传这个字段，这两种模式会退化成 0（不报错，只是不生效）。"FX 是乘法写法时的新汇率做差"模式不受影响，因为只需要数值化的 `exchangeRate`。

---

### 6. Fee / Platform Fee 语义（2026-08 起，含 Payment History 优化后的最新口径）

| 项目 | 语义 |
|---|---|
| Fee（`middlemanAmount`） | **第二（leg2）币种面值，不换汇**。落库分录金额是 `feePortion = Fee − PlatformFee`（净额，见下）——**middleman 实收的是净额**，platform 抽走的那部分不会算进 middleman 的收入。 |
| Platform Fee（`platformFeeAmount`） | 第二币种面值，恒正数。**有自己独立的分录行**（`CHARGE {ccy} {amt} PLATFORM FEE`），单边只记在 `leg2.fromAccountId()` 上，没有对手方（不给任何账户 +）。 |

**这跟本文档更早版本描述的"Platform Fee 不产生独立分录行，只影响 Fee 净额"已经不一样了**——2026-08 中旬这版继续迭代，改成了 Platform Fee 也要有自己看得见的一行记录（原因：需要在 Payment History 里单独显示 `CHARGE ... PLATFORM FEE` 这条，而不是只靠 Fee 净额隐性体现）。`transactions_rate.platform_fee_amount` 头表字段依然保留（记录原始输入值，Payment History 合并展示时要用它把 Fee 分录的净额"还原"成满额，见第 10 节）。

**Fee 口径变更历史**：这个字段以前是"第一币种输入，落 Win/Loss 前要 `× exchangeRate`"（跟 legacy PHP 旧版一致）。2026-08 改成第二币种面值不换汇。

**Service Fee remark 已移除（2026-08 中旬）**：以前 leg1（toAccount1）会写一条 `CHARGE {leg2币种} {fee} SERVICE FEES` 的 remark（`TransactionSubmitServiceImpl.formatServiceFeeRemark()`），是旧算法里"Service Fee 会自动从 leg1 to account 扣"这件事的留痕。新算法不会再自动扣这笔——Service Fee 已经通过 Fee 分录（第 7 节）体现在 leg2 from account 上，用户填 Fee 只是为了让金额、middleman 收入算准，跟 leg1 无关，这条 remark 因此失去意义，已删除（`formatServiceFeeRemark()` 方法整个移掉，`leg1Txn` 的 remark 恢复成用户自己填的 `remark` 原样）。

---

### 7. 落库分录

一次 RATE 提交最多写 5 笔 `transactions`，全部共用同一个 `rate_group_id`：

| 分录 | 账户（account_id / from_account_id） | 金额 | 说明 |
|---|---|---|---|
| leg1 | `leg1ToAccountId` / `leg1FromAccountId` | `leg1Amount` | Cr/Dr（To −，From +，同 PAYMENT），恒写 |
| leg2 | `leg2ToAccountId` / `leg2FromAccountId` | `grossTo`（**flat 毛额**，`amountFrom × exchangeRate`） | Cr/Dr，恒写。**不再是"毛额减 Middle-Man"的净额**——leg2 永远是干净的毛额，扣减全部靠下面几笔额外分录冲抵 `leg2.fromAccountId()` |
| Rate 分录 | To=`leg2.fromAccountId()`，From=`middleman.accountId` | `ratePortion` | Win/Loss（From=middleman +，To=leg2 from account −）。仅 `ratePortion != null`（Rate-Mul 佣金 >0）才写 |
| Fee 分录 | To=`leg2.fromAccountId()`，From=`middleman.accountId` | `feePortion = Fee − PlatformFee` | Win/Loss，跟 Rate 分录同样是双边（middleman +，leg2 from account −）。仅 `feePortion != null`（即 `Fee − PT > 0`）才写 |
| Platform Fee 分录 | To=`leg2.fromAccountId()`，From=`NULL`（无对手方） | `platformFeeAmount` | Win/Loss，单边，只记在 leg2 from account 上。仅 `platformFeeAmount != null` 才写 |

**跟改动前的关键差异**：
- Rate 分录以前的对手方是 `leg2.toAccountId`（"leg2 payer"），现在改成 `leg2.fromAccountId()`——扣减方从"付款方"转移到"收款方（from account）"，因为这就是你实际要追踪"这个账户还欠多少 / 净拿到多少"的那个账户。
- Fee 分录以前是**单边**只记 middleman +（`from_account_id = NULL`），现在改成跟 Rate 分录一样的**双边**结构，才能同时体现"middleman 收入"和"leg2 from account 被扣了多少"两件事。
- Platform Fee 以前**没有**自己的分录，现在**有**了，且是单边（模式跟旧版的 Fee 分录一样：只给一方记账，没有对手方）。

三笔 Middle-Man 分录都用第二币种（`leg2.currency`）。`transactions_rate` 头表**始终**记录 `middleman_rate` / `middleman_rate_expression` / `middleman_amount`（Fee 原始值）/ `platform_fee_amount`（PT 原始值），跟是否真的写了分录无关——即使某次提交因为 Rate-Mul 倒贴或 PT 吃光 Fee 导致对应分录没写，头表依然留痕。

---

### 8. Description 文案

沿用 [`transaction-description-rules.md`](./transaction-description-rules.md) 的规则，Middle-Man 那部分本次改了 rate token 的生成方式：

```text
Fee:            MARKUP X {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 除法模式：  MARKUP /{divisor} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 乘法模式：  MARKUP x{value} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Platform Fee：  CHARGE {ccy2} {amount} PLATFORM FEE
```

以前是直接打印裸乘数（`{middlemanRate}`），现在按 `ParsedRate.mode()` 加 `/` 或 `x` 前缀，跟前端 `middlemanRateDesc` 的展示风格一致，避免"除以 1.55"和"乘以 1.55"在历史记录里分不清。

Platform Fee 走的是完全独立的 `formatPlatformFeeDescription()`，**不是** `formatMiddlemanMarkupDescription()`——不带 `MARKUP` 前缀、不带 `FROM {account}`，就是固定的 `CHARGE {ccy} {amt} PLATFORM FEE`。查询层用这个固定文案（`LIKE 'CHARGE % PLATFORM FEE'`）识别这一类分录，详见第 10、11 节。

---

### 9. Schema：`transactions_rate`

| 列 | 类型 | 说明 |
|---|---|---|
| `exchange_rate` | `DECIMAL(18,8)` | 数值化汇率 |
| `rate_expression` | `VARCHAR(64)` | FX 原始文本 |
| `middleman_account_id` | `INT UNSIGNED` | 可空 |
| `middleman_rate` | `DECIMAL(18,8)` | 除法模式存除数，乘法模式存乘数/新汇率原值 |
| `middleman_rate_expression` | `VARCHAR(32)`（**新增**） | Rate-Mul 原始文本，如 `/1.55` |
| `middleman_amount` | `DECIMAL(25,8)` | Service Fee 面值，**leg2 币种**（列注释已同步更新，语义变更见第 6 节） |
| `platform_fee_amount` | `DECIMAL(25,8)`（**新增**） | Platform Fee 面值，leg2 币种。**2026-08 中旬起也是独立分录（第 7 节）的落库金额来源**，不再只是"影响 Fee 净额的头表字段" |

迁移文件：[`sql/migrate_rate_platform_fee_and_ratemul.sql`](../backend/src/main/resources/sql/migrate_rate_platform_fee_and_ratemul.sql)（幂等性：只能在这两列不存在时执行一次）。全新装库直接看 `sql/schema.sql`。

不在这套 schema 里：legacy PHP 的 `transactions_rate_details` / `transaction_entry`（沿用 `migrate_rate_tables_optimized.sql` 就定下的简化）。

---

### 10. Payment History 展示层：把明细"合并"回 leg2 from account 的净额

`submitRate` 落库时是"毛额 + 多笔扣减分录"的明细结构（见第 7 节），但 leg2 from account 自己查 Payment History 时，不想看到一堆 `MARKUP ...` 明细行，而是想看到「一行净额（毛额扣掉 Rate-Mul + Service Fee） + 一行 Platform Fee」。这个"从明细合并成净额"的转换，全部发生在 **展示层**（`service/impl/TransactionHistoryServiceImpl.java`），不改落库数据。

**入口**：`buildDomainPaymentHistorySlice()` 在把 `findDomainPaymentHistoryLines` 查出来的每一行做完 `applyRateHistoryPresentation` 之后，调用 `mergeRateMiddlemanDeductionsIntoMainLeg(lines, accountId)`。

**做法**：
1. 找出这批记录里，`from_account_id == accountId` 且不是 Middle-Man 分录的那一行——这就是 leg2 主记录（因为只有 leg2 这一笔，被查询账户会出现在 `from_account_id` 位置）。
2. 遍历所有「双边 Middle-Man 分录」（`from_account_id != NULL` 且 `to_account_id == accountId`，也就是 Rate 分录和 Fee 分录），把它们的 `signedAmount` 直接加进 leg2 主记录，然后从最终列表里删掉这些行（不再单独显示）。
3. **Fee 分录要额外修正**：它落库时存的是 `feeNet = Fee − PlatformFee`（净额，见第 6 节），如果直接把这个净额并进 leg2，会导致 Platform Fee 被"合并进主记录"和"自己那行 `CHARGE ... PLATFORM FEE`"重复扣两次。所以判断这一行是不是 Fee 类（`isRateMiddlemanFeeKind`）之后，再把这个 rate_group 的 Platform Fee 原始金额减回去一次，把净额还原成满额——保证主记录只扣「Rate-Mul + Service Fee 满额」，Platform Fee 的影响完全交给它自己那一行。
4. Platform Fee 分录（单边，`from_account_id IS NULL`）不参与合并，原样保留成独立一行。

**列/ID PRODUCT 路由**（`toHistoryRow()`）：leg2 主记录本来就走 Cr/Dr（不是 Middle-Man 分录），合并后金额变了但列不变；Platform Fee 因为 `fromAccountId == null` 被识别出来，固定走 **Cr/Dr** 列、`product = "Fee"`；Rate 分录如果是 middleman 自己查（`fromAccountId == accountId`，即他在双边分录里的角色），则仍然走 **Win/Loss** 列、`product = "RATE"`，不受这次改动影响。

**mapper 配合**：`TransactionHistoryMapper.xml` 的 `rateMiddlemanKind` 分类 CASE 加了一条 `description LIKE 'CHARGE % PLATFORM FEE' → 'FEE'`，纯粹给 ID PRODUCT 列用，不影响任何金额/正负号计算。

**Rate-Mul token 展示（middleman 自己视角，2026-08 下旬新增）**：`formatRateMiddlemanMarkupDescription()` 拼 description 时，Rate 分录（非 Fee）的 rate token 不再直接印 middleman 输入的原始值，改用 `formatRateMiddlemanRateToken()` 按模式算出一个差值：
- **乘法模式**（FX 本身不是除法写法）：`原汇率 − middleman 输入`，例如 3 − 2.9 = `0.1`。
- **除法模式**（此时 FX 本身也必然是除法写法，否则佣金算出来是 0、根本不会写这笔分录）：`middleman 除数 − FX 除数`，例如 1.305 − 1.32 = `-0.015`。
- 两种都四舍五入到 6 位小数，位数不够按实际位数显示，不补零（复用 `formatRateHistoryDecimal`）。

这个改动**只影响 middleman 自己查 Payment History 时看到的文字**——落库的审计 description（`TransactionSubmitServiceImpl.formatMiddlemanMarkupDescription()`）还是印原始输入值，不受影响；leg2 from account 自己看到的合并视图也不受影响，因为 Rate/Fee 两行早就被合并进主记录了（见上文），根本不会显示这个 token。

数字示例见第 14 节。

---

### 11. CONTRA 汇总页：同一笔扣减也搬进 Cr/Dr

Transaction Payment 页面顶部那个「Account / B-F / Win-Loss / Cr-Dr / Balance」汇总表，走的是完全独立的一套纯 SQL 聚合（`service/impl/TransactionSearchServiceImpl.java` + `mybatis/TransactionSearchMapper.xml`），不经过上面第 10 节的 Java 合并逻辑，也不是"按查询账户视角挑一行行"的模式——它一次性把租户下所有账户按 `account_id + currency` 聚合成一行，Win/Loss 和 Cr/Dr 是两条完全分开跑的 SQL 分别产出再合并。

原本 `aggregateManualRateMiddlemanWinLoss` 有一条 UNION 分支，专门把「leg2 from account 的 `−amount`」（Rate-Mul + Fee 双边分录里 To 那一侧）算进 **Win/Loss**。这次改动把这条分支整个搬到新建的查询 `aggregateManualRateMiddlemanCrDr`，输出目标从 `winLossAmount` 换成 `crDrAmount`，在 `TransactionSearchServiceImpl.buildDomainPaymentSearchSlice()` 里跟 leg2 自己的毛额 Cr/Dr 行（`aggregateDomainPaymentCrDr`）合并加总。Middleman 自己那条 `+amount`（From/middleman 分支）留在原查询里，继续算 Win/Loss，不受影响。

**注意**：这里**没有**额外处理 Platform Fee 的加减——因为 Fee 分录落库金额本来就是 `Fee − PlatformFee`（净额），CONTRA 这边直接照单全收这个净额，数字天然就是对的（跟第 10 节 Payment History 里"先还原满额、再单独加 Platform Fee 一行"殊途同归，算出来的总数一致，只是 CONTRA 没有"单独一行"的概念，不需要拆开）。Platform Fee 单边分录本身在这套聚合里目前不会被任何分支捞到（既不是双边、也不匹配这个文件里 `rateMiddlemanFeeDescription` 的旧 pattern），CONTRA 汇总总数因此不含 Platform Fee 的字面数字，但因为它已经隐含在 Fee 净额里了，总数依然正确。

---

### 12. 已知限制 / 后续

1. **DIVIDE 模式依赖前端传 `rateExpression`**——见第 5 节，前端已经在 `buildRatePayload` 里加了这个字段（`rate_expression`），只要走新版前端就没问题；如果有别的调用方（比如未来的 mobile）没传这个字段，这两种模式会静默退化成 0。
2. **精度上限是 8 位小数**（`RATE_AMOUNT_SCALE=8`），沿用本仓库既有约定；`count168test` 参考文档里 2026-08 之后写的是"6 位截断"，那是 legacy PHP 的现行规则，不适用于本仓库。
3. **leg2 不支持两侧金额不同**——PHP 模型里"有 Rate-Mul 乘数时 Transfer 两侧金额可以不等"这个场景，在本仓库里被简化成"只有一个对称金额"；2026-08 起 leg2 恒记 flat 毛额，Rate-Mul/Fee/Platform Fee 的扣减全部体现在额外分录，不再体现在 leg2 本身。

---

### 13. 相关文件

**Backend**

- `service/impl/TransactionSubmitServiceImpl.java`（`submitRate` / `resolveMiddleman` / `formatMiddlemanMarkupDescription` / `formatPlatformFeeDescription`）
- `service/impl/TransactionHistoryServiceImpl.java`（`mergeRateMiddlemanDeductionsIntoMainLeg` / `toHistoryRow` / `applyRateMiddlemanHistoryPresentation` / `formatRateMiddlemanRateToken`）
- `service/impl/TransactionSearchServiceImpl.java`（`buildDomainPaymentSearchSlice`）
- `util/RateMulCalculator.java`
- `util/TransactionMoneyFormat.java`
- `dto/TransactionSubmitDTO.java`
- `entity/TransactionRate.java`
- `dao/TransactionRateDao.java` + `mybatis/TransactionRateMapper.xml`
- `dao/TransactionSearchDao.java` + `mybatis/TransactionSearchMapper.xml`（`aggregateManualRateMiddlemanWinLoss` / `aggregateManualRateMiddlemanCrDr`）
- `mybatis/TransactionHistoryMapper.xml`（`rateMiddlemanFeeDescription` / `rateMiddlemanKind`）
- `sql/schema.sql` / `sql/migrate_rate_platform_fee_and_ratemul.sql`

**Related docs**

- [transaction-amount-precision.md](./transaction-amount-precision.md)
- [transaction-description-rules.md](./transaction-description-rules.md)
- [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md) —— 前端 payload 映射

---

### 14. 数字示例

假设：`leg1Amount=1000 SGD`，`exchangeRate=3`（乘法写法），`gross=3000 MYR`；Middle-Man 账户已选，`Rate-Mul="2.9"`（新汇率模式，比原汇率 3 小 0.1），`Fee=10`（第二币种面值），`PlatformFee=1.5`。账户：leg1 是 OK→OK2（SGD），leg2 是 OK2→OK（MYR，OK 是 `fromAccountId`），middleman 是 OK3。

```text
rateMulCommission = (3 − 2.9) × 1000 = 100          → ratePortion = 100（>0，写分录）
feeNet             = 10 − 1.5 = 8.5                  → feePortion = 8.5（>0，写分录）
total              = 100 + 8.5 = 108.5（< grossTo=3000，校验通过）

写入 5 笔（同一个 rate_group_id）：
  leg1:          SGD 1000，account=OK，  from_account=OK2
  leg2:          MYR 3000（flat 毛额）， account=OK2， from_account=OK
  Rate 分录:     MYR 100，  account=OK，  from_account=OK3   "MARKUP x2.9 SGD 1000 > MYR | FROM OK"
  Fee 分录:      MYR 8.5，  account=OK，  from_account=OK3   "MARKUP X SGD 1000 > MYR | FROM OK"
  Platform Fee:  MYR 1.5，  account=OK，  from_account=NULL  "CHARGE MYR 1.5 PLATFORM FEE"

transactions_rate 头表：
  middleman_rate = 2.9, middleman_rate_expression = "2.9"
  middleman_amount = 10, platform_fee_amount = 1.5
```

**OK（leg2 from account）看到的两种视图**：

| 视图 | 呈现方式 | 结果 |
|---|---|---|
| Payment History（第 10 节合并后） | RATE 一行 Cr/Dr（毛额 3000 − Rate-Mul 100 − Fee **满额** 10）+ Fee 一行 Cr/Dr（Platform Fee +1.5） | `2890.00` + `+1.50` = **2891.50** |
| CONTRA 汇总（第 11 节迁移后） | 一行 Cr/Dr（毛额 3000 − Rate-Mul 100 − Fee **净额** 8.5），Win/Loss = 0 | Cr/Dr **2891.50** |

两条路径算法不同（一个先还原满额再单独加 Platform Fee 一行，一个直接用净额），但代数上等价（`3000−100−10+1.5 = 3000−100−8.5 = 2891.5`），**总数必须始终一致**——以后改这块任何一边的公式，务必同时验算另一边，防止两个页面数字对不上。

middleman（OK3）看到的是未合并的原始视角：Rate `+100`、Fee `+8.5`，都走 Win/Loss，不受第 10、11 节改动影响。

---

## 27. Transaction Amount Precision

> 原始独立文件：`docs/transaction-amount-precision.md`（内容已合并于此；原文件已改为跳转说明）


金额精度约定：**存真值，看 2 位。**  
修改入库 scale、API 金额序列化、或 Transaction / Bank Process / Domain 相关金额行为时，必须同步更新本文档，并保持后端与 `Count-frontend` 对齐。

### 原则

| 环节 | 行为 |
|------|------|
| 入库 / 计算 / API | 高精度真值；**禁止**为存储或响应做 round-to-2 |
| 普通交易金额上限 | 小数点后最多 **6** 位；超过则 **拒绝**（不截断、不四舍五入到上限） |
| RATE 交易金额上限 | 小数点后最多 **8** 位；超过则 **拒绝** |
| 汇率 / Middle-Man rate | 最多 **8** 位；超过则 **拒绝** |
| UI 展示 | 一律 **HALF_UP → 2** 位（仅展示，不回写库） |
| 余额 / 汇总 | **高精度累加**，展示时再 round 2 |

DB 字段多为 `DECIMAL(25, 8)`；业务上限（普通 6 / RATE 8）比列定义更严或持平。

### 交易类型与上限

#### 普通交易（≤ 6）

含手动提交与系统进账：

- `PAYMENT` / `CLAIM` / `CLEAR` / `CONTRA` / `PROFIT` / `ADJUSTMENT`
- Bank Process → Accounting Due → `transactions.amount`
- Domain fee charge → `transactions.amount`
- Bank Process 表单上的 Buy / Sell / Profit / Insurance / Profit Sharing 等（写入 process 后进 Due）

#### RATE 交易（≤ 8）

- Leg1 / Leg2 `amount`
- Middle-Man Fee / Platform Fee（**第二（leg2）币种面值，不换汇**，2026-08 起；旧版是第一币种要 `× exchangeRate`，已废弃）
- `exchangeRate` / Rate-Mul 除数或乘数（见 [transaction-rate-middleman-logic.md](./transaction-rate-middleman-logic.md)）
- 中间结果：Rate-Mul 佣金（`RateMulCalculator.computeCommission`，可为负）、`Fee − PlatformFee`、`gross − middleman` 等

### 后端

#### 共享入口

`backend/src/main/java/com/eazycount/util/TransactionMoneyFormat.java`

| 方法 | 用途 |
|------|------|
| `NORMAL_AMOUNT_SCALE` (=6) / `RATE_AMOUNT_SCALE` (=8) | 上限常量 |
| `formatMoney` | API 序列化：**plain 真值**（不再 round 2） |
| `requireNormalAmount` / `requireRateAmount` / `requireMaxScale` | 客户端输入：超限抛业务错，原样保留 |
| `normalizeComputedNormal` / `normalizeComputedRate` | 系统计算结果：不超过上限则原样；**仅当 scale 超过上限**时 HALF_UP 到该上限（仍不做 round-to-2） |
| `truncateToScale` / `truncateNormalAmount` / `truncateRateAmount` | Data Capture Summary：**截断 ROUND_DOWN**（6 / 8）；与 Transaction 的 HALF_UP normalize **不同** |
| `add` / `nz` / `strip` | 高精度运算 |

#### 主要调用点

- `TransactionSubmitServiceImpl`：提交解析与 RATE 校验（容差 `1e-8`）
- `AccountingDueServiceImpl`：Due 进账金额（`normalizeComputedNormal`）
- `DomainFeeChargeServiceImpl`：Domain 进账金额
- `TransactionHistoryServiceImpl` / `TransactionSearchServiceImpl`：History / Search 返回真值；running balance 高精度累加后再 `formatMoney`

#### 示例

| 场景 | 输入 / 计算 | 入库 | API 返回 | UI（前端） |
|------|-------------|------|----------|------------|
| 普通 PAYMENT | `10.123456` | `10.123456` | `10.123456` | `10.12` |
| 普通 PAYMENT | `10.1234567` | 拒绝 | — | — |
| RATE leg | `10.12345678` | `10.12345678` | `10.12345678` | `10.12` |
| RATE leg | `10.123456789` | 拒绝 | — | — |
| Due 折算 `price × ratio` | 结果小数 ≤6 | 原样 | 真值 | round 2 |
| Due 折算 | 结果小数 >6 | HALF_UP 到 6 位后入库 | 真值 | round 2 |

### 前端（Count-frontend）

#### 共享入口

`src/utils/money/moneyDecimal.js`（`MoneyDecimal`）

| 方法 | 用途 |
|------|------|
| `UI_SCALE` (=2) | 仅展示 |
| `NORMAL_AMOUNT_SCALE` / `RATE_AMOUNT_SCALE` | 与后端一致 |
| `formatUiFixed` / `formatUiMoney` | 展示 HALF_UP 2（后者带千分位） |
| `toPlainAmount` / `requireNormalAmount` / `requireRateAmount` | 提交真值；超限抛错 |
| `normalizeComputedRate` | 表达式/中间结果：超 8 位才 HALF_UP 到 8 |

Transaction 展示封装：`src/pages/transaction/lib/transactionFormat.js`  
（`formatTransactionGridMoneyHalfUp`、`formatRateAmount` 等均为 **display-only**）。

#### 提交 vs 展示

- **提交**：普通走 `requireNormalAmount`；RATE legs / fee / rate 走 `requireRateAmount` / plain，**禁止**再 `formatFixedHalfUp(..., 2)` 后作为 payload。
- **RATE 表单**：state 存真值；只读金额框用 `formatRateAmount`（= UI 2 位）显示。
- **列表 / History / 汇总**：行内与 totals 保持高精度；`TransactionWinLossCell` 等在 render 时 round 2。
- **Bank Process**：表单/API 存 plain ≤6；列表单元格仍 `formatBankMoneyFixed2`（展示 2 位）。

#### 后续页面约定

凡金额相关页面：

1. 展示 → `MoneyDecimal.formatUiMoney` / `formatUiFixed`（或 Transaction 已有 display helper）
2. 提交 / 写入 → `requireNormalAmount` 或 `requireRateAmount`
3. 累加 → `MoneyDecimal.add` 等，**不要**先 round 2 再加

不要在业务里直接写 `toFixed(2)` / 对 payload 做 round-to-2。

### Data Capture Summary（截断规则，区别于 Transaction 的 HALF_UP）

Summary processed amount 走**截断（ROUND_DOWN，向零）**，不是 HALF_UP。  
修改 Summary 金额算法、Submit payload、或 ±0.05 门槛时，必须同步更新本节，并保持后端 `SummaryAmountFormat` 与前端 `summaryRowAmount.js` 对齐。

#### 规则

| 环节 | 行为 |
|------|------|
| 基础 amount | 公式结果，6 位精度域 |
| 走 rate（行有 rateValue / 勾选用全局 rate） | `base × / rate` 中间结果**截断到 8 位** |
| 最终 processed amount | 一律**截断到 6 位**（8 位的先 8→6 截断）再入 `data_capture_line.processed_amount` / Submit payload |
| UI 展示 | HALF_UP 2，仅展示，**不进 payload、不进计算、不作 fallback** |
| Submit 门槛 | 合计 HALF_UP 到 2 位后须在 **±0.05**（含端点） |
| 前后端数字 | **允许不一致**：后端/payload 为 6/8 真值截断；前端格子为 HALF_UP 2 |

rate 表达式形式：`*N` 乘、`/N` 除、`N` 乘；空 / 非法 / 0 视为无 rate（base 原样）。

#### 示例

| 场景 | 计算 | 中间（rate） | 入库 / payload | UI |
|------|------|--------------|----------------|-----|
| 无 rate | `1.23456789` | — | `1.234567`（6 位截断） | `1.23` |
| `/3` | `0.1 / 3` | `0.03333333`（8） | `0.033333`（6） | `0.03` |
| `*1.1` | `0.12345678 × 1.1` | `0.13580245`（8） | `0.135802`（6） | `0.14` |
| 合计门槛 | 高精度累加后 HALF_UP 2 | — | 不因门槛改写入库值 | `0.05` 过 / `0.06` 拒 |

#### 后端入口

- `util/SummaryAmountFormat.java`：`applyRateExpression`（8 位截断）→ `finalizeProcessedAmount` / `computeProcessedAmount`（6 位截断）→ `isTotalWithinSubmitTolerance`（±0.05）
- 截断原语：`TransactionMoneyFormat.truncateToScale` / `truncateNormalAmount` / `truncateRateAmount`（`RoundingMode.DOWN`）
- Spring Summary Submit 落库时必须走这套重算，**不直接信**前端数值
- 单测：`SummaryAmountFormatTest`

#### 前端入口（Count-frontend，与后端同管线）

| 文件 | 职责 |
|------|------|
| `datacapturesummary/table/summaryRowAmount.js` | 核心算法：`applyRateToRowAmount`、`truncateRateAmountTo8Decimals`、`truncateProcessedAmountTo6Decimals`、`resolveSubmitProcessedAmount`、`recalculateRowAmounts` |
| `datacapturesummary/submit/buildSubmitRowsFromModel.js` | Submit payload：`processedAmount` 为 **6 位字符串**（非 `Number`、非 round-2） |
| `datacapturesummary/submit/summarySubmitTotalPure.js` | 合计门槛 ±0.05（HALF_UP 2 后校验） |
| `datacapturesummary/submit/summarySubmitRowGuard.js` | 行守卫用 6 位 plain amount（`MoneyDecimal` 比较） |
| `datacapturesummary/formula/summarySaveTemplatePure.js` | `last_processed_amount`：**6 位截断**（禁止 round-to-2） |
| `datacapturesummary/formula/editFormulaFormState.js` | 状态：`processedAmount` / `baseProcessedAmount` 真值或 6 位；`processedAmountDisplay` 才 HALF_UP 2 |

**禁止**：用 `processedAmountDisplay`（HALF_UP 2）作为 Submit / 合计 / 模板入库 fallback。

单测：`summaryRowAmount.test.js`、`summarySubmitRowGuard.test.js`。

### 历史数据

改规则前已按 2 位入库的旧数据继续按 2 位真值存在；新数据才可能出现 6/8 位小数。Summary 模板里旧的 `last_processed_amount` 可能仍是 2 位；新写入为 6 位截断，展示层统一 HALF_UP 2。

### Related docs

- [datacapture-spring-api.md](./datacapture-spring-api.md) — Data Capture Spring API / Summary Submit
- [transaction-description-rules.md](./transaction-description-rules.md) — `transactions.description` audit storage vs History UI
- [transaction-rate-middleman-logic.md](./transaction-rate-middleman-logic.md) — RATE Middle-Man / Rate-Mul / Platform Fee 完整逻辑

### 相关文件（速查）

**Backend**

- `util/TransactionMoneyFormat.java`
- `util/RateMulCalculator.java`
- `util/SummaryAmountFormat.java`
- `service/impl/TransactionSubmitServiceImpl.java`
- `service/impl/TransactionHistoryServiceImpl.java`
- `service/impl/TransactionSearchServiceImpl.java`
- `service/impl/AccountingDueServiceImpl.java`
- `service/impl/DomainFeeChargeServiceImpl.java`

**Frontend**

- `utils/money/moneyDecimal.js`
- `pages/transaction/lib/transactionFormat.js`
- `pages/transaction/lib/transactionSubmitHelpers.js`
- `pages/transaction/lib/transactionPaymentLogic.js`
- `pages/transaction/hooks/useTransactionForm.js`
- `pages/bankprocesslist/lib/bankProcessHelpers.js`
- `pages/bankprocesslist/bankProcessListApi.js`
- `pages/datacapturesummary/table/summaryRowAmount.js`
- `pages/datacapturesummary/submit/buildSubmitRowsFromModel.js`
- `pages/datacapturesummary/submit/summarySubmitTotalPure.js`
- `pages/datacapturesummary/submit/summarySubmitRowGuard.js`
- `pages/datacapturesummary/formula/summarySaveTemplatePure.js`
- `pages/datacapturesummary/formula/editFormulaFormState.js`

---

## 28. Transaction Description Storage

> 原始独立文件：`docs/transaction-description-rules.md`（内容已合并于此；原文件已改为跳转说明）


`transactions.description` 入库约定。修改提交写入或 History 展示拼装时，必须同步更新本文档。

### 原则

| 用途 | 行为 |
|------|------|
| **数据库入库** | 提交时写入可读审计文案（账户用 **name**，空则回退 account code） |
| **History UI** | **不受入库文案影响**：读接口仍按账户视角重算展示（账户用 **code**），与改前一致 |
| Domain / Bank Process | 系统已有 description（如 `PAY DOMAIN FEE`、Due 文案）保持原样，不套用本规则 |

### 入库格式

#### RATE 转账腿（leg1 / leg2）

```text
EXCH RATE {rate} {ccy1} {amount} > {ccy2} | FROM {fromAccountName} TO {toAccountName}
```

例：`EXCH RATE 3 MYR 1010 > SGD | FROM Alice TO Bob`

- `{rate}`：优先 `rateExpression`，否则 exchange rate plain
- `{amount}`：leg1 金额（高精度 plain，与 `TransactionMoneyFormat.formatMoney` 一致）
- 每条腿用**该腿**自己的 From / To 账户名

#### RATE Middle-Man

与 History 收款方 MARKUP 展示同形，账户用 **name**。Rate token 按 Rate-Mul 模式（`RateMulCalculator.ParsedRate.mode()`）区分，不再是裸乘数：

```text
Fee:                MARKUP X {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 除法模式：      MARKUP /{divisor} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 乘法模式：      MARKUP x{value} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
```

例：`MARKUP X MYR 1010 > SGD | FROM Alice`、`MARKUP x2.93 MYR 1010 > SGD | FROM Alice`

详见 [transaction-rate-middleman-logic.md](./transaction-rate-middleman-logic.md) 第 5 节。旧数据可能仍为 `RATE_MIDDLEMAN_FEE` / `RATE_MIDDLEMAN_RATE`；查询需兼容两种。

#### RATE Platform Fee（独立分录，2026-08 中旬新增）

不套用 `MARKUP` 那套格式，固定文案，不带账户名：

```text
CHARGE {ccy2} {amount} PLATFORM FEE
```

例：`CHARGE MYR 1.5 PLATFORM FEE`。单边分录（`from_account_id` 为空），只挂在 `leg2.fromAccountId()` 上。查询层用 `description LIKE 'CHARGE % PLATFORM FEE'` 识别，详见第 26.10、26.11 节。

#### CONTRA / PAYMENT / CLAIM / CLEAR / PROFIT

```text
{TYPE} FROM {fromAccountName} TO {toAccountName}
```

例：`CONTRA FROM Alice TO Bob`

#### ADJUSTMENT

不变：

```text
ADJUSTMENT - WIN/LOSS
```

#### WIN / LOSE（Data Capture Summary Submit）

```text
{ProcessName}: {formula}
```

例：`SALARY: 1111 * 2222`

- `{ProcessName}`：`process.code`（Bank 固定码如 `SALARY`；Games 为 process 业务码）
- `{formula}`：该行 `data_capture_line.formula` 原文，**原样保留**（括号、运算符等不做任何改写/裁剪）；行未配置公式时回退为该行最终金额（`TransactionMoneyFormat.formatMoney`）
- 不区分 WIN/LOSE 用不同文案——方向已由 `transaction_type` 本身表达

### History 展示（不改业务观感）

读 History 时：

- RATE 转账腿：仍按视角拼 `EXCH RATE … | FROM {code}` **或** `… | TO {code}`
- Middle-Man：仍拼 `MARKUP … | FROM {leg1ToCode}`——但 leg2 from account 自己查看时，Rate-Mul / Fee 这两条 `MARKUP` 分录会被合并进 leg2 主记录，不再单独出现（见 26.10 节 `mergeRateMiddlemanDeductionsIntoMainLeg`）
- Platform Fee：单边分录，固定文案原样展示，不重写、不套用视角逻辑
- CONTRA 等：仍按视角拼 `{TYPE} FROM {code}` **或** `{TYPE} TO {code}`
- 若 DB 已是审计串 `{TYPE} FROM … TO …` / `EXCH RATE … | FROM … TO …`，History **覆盖为**上述视角文案后再返回
- Domain 等其它 description（不匹配审计串）原样保留

### 实现入口

| 层 | 文件 |
|----|------|
| 提交写入 | `TransactionSubmitServiceImpl`（`formatTransferDescription` / EXCH / MARKUP / `formatPlatformFeeDescription`）+ `RateMulCalculator`（Rate-Mul 模式解析） |
| Data Capture Summary Submit 写入 | `DataCaptureSummaryServiceImpl`（`toTransaction`） |
| History 重写 + 明细合并 | `TransactionHistoryServiceImpl`（`applyRateHistoryPresentation` / `applyManualTransferHistoryPresentation` / `mergeRateMiddlemanDeductionsIntoMainLeg`） |
| Middle-Man / Platform Fee 识别 | `TransactionHistoryMapper.xml` + `TransactionSearchMapper.xml`（`rateMiddlemanFeeDescription` 兼容 `RATE_MIDDLEMAN_FEE` / `MARKUP X %` / `CHARGE % PLATFORM FEE` 三种） |

### 与金额精度

金额在 description 中的写法遵循 [transaction-amount-precision.md](./transaction-amount-precision.md) 的 plain 真值序列化（不强制 round-to-2）。

### Related docs

- [transaction-rate-middleman-logic.md](./transaction-rate-middleman-logic.md) — RATE Middle-Man / Rate-Mul / Platform Fee 完整逻辑（决策树、分录规则、schema）

---

## 29. Customer Report — Spring API 迁移说明

> 原始独立文件：`docs/customer-report-spring-migration.md`（内容已合并于此；原文件已改为跳转说明）


> **前端仓库**：`../Count-frontend/`
> **后端前缀**：`/api/report/*`
> **最后更新**：2026-08-13

---

### 1. 背景

Customer Report 原本完全跑在旧版 PHP（`count168/api/reports/customer_report_api.php`），直接聚合旧表
`data_capture_details`，**不经过 `transactions`（Payment History）这一层**。

本次迁移把「Customer Report 只统计 Payment History 里 Product = DATA CAPTURE 的记录」这个需求做成新的
Spring 端点：从 `transactions` 表按 Payment History 对 DATA CAPTURE 的同一套判定口径聚合 Win/Lose，
不再依赖已被 `TABLE_MIGRATION.md` 标记淘汰的 `data_capture_details`。

---

### 2. 原则

- **Tenant 模型**：请求体传数字 `tenantId`（`tenant.id`），不使用旧的 `company_id` / `group_id` /
  `report_scope` / `view_group` 等 scope 参数——与 Maintenance / Payment History 已迁移端点一致。
- **DATA CAPTURE 判定口径**：`transaction_type IN ('WIN','LOSE') AND bank_process_posted_id IS NULL`，
  与 `TransactionHistoryMapper.xml` 的 `findDataCaptureHistoryLines` / `aggregateDataCaptureBfByAccount`
  完全一致（Payment History 把这批记录标成 `product = "DATA CAPTURE"` 用的就是这个条件）。
- **不校验 `approval_status`**：Data Capture 走 Summary Submit 落库即生效，从不需要审批，所以聚合时
  不加 `approval_status = 'APPROVED'` 这个条件（这点和 Bank Process 的聚合口径不同）。
- **一个 tenant 一次请求**：Group（AP/IG）+ Company（95/AG/CX/RS/VG）之间的跨租户汇总，和其它已迁移页面
  一样由前端对每个 tenant 循环请求再合并，后端从未支持过跨租户查询。

---

### 3. Spring 端点

| 能力 | 方法 | 路径 | Body |
|------|------|------|------|
| Customer Report 列表 | POST | `/api/report/customer-report/list` | `CustomerReportDTO`（见 §4） |

成功响应：`{ "success": true, "message": "...", "data": [...] }`；失败：`{ "success": false, "message": "..." }`（HTTP 200，与 `MaintenanceController` 同款）。

---

### 4. 请求 / 响应结构：`CustomerReportDTO`

同一个类既是请求体也是响应行（沿用 `MaintenanceTransactionDTO` 的复用风格，未拆 Request/Result 两个类）。

**请求字段**

```json
{
  "tenantId": 42,
  "currencyCodes": ["MYR", "SGD"],
  "dateFrom": "2026-01-01",
  "dateTo": "2026-08-13",
  "accountId": null,
  "showAll": false
}
```

| 字段 | 说明 |
|------|------|
| `tenantId` | 必填，> 0 |
| `currencyCodes` | 可选；`null` / 空数组 = 该账号名下 **所有** 已分配币种（`account_currency`），对应 UI 的「Show All Currencies」 |
| `dateFrom` / `dateTo` | 必填，`yyyy-MM-dd` 或 `d/M/uuuu`（`TransactionDateParse`） |
| `accountId` | 可选；`null`/`0` = All Accounts |
| `showAll` | Show All 开关（见 §6） |

**响应行**（`data` 数组，最后一笔是合成的 Total 行）

```json
[
  { "accountRowId": 101, "accountCode": "1SLOT", "accountName": "1SLOT", "currencyCode": "MYR", "winAmount": 7.71, "loseAmount": -0.76 },
  { "totalRow": true, "winAmount": 4960107.92, "loseAmount": -4960107.95 }
]
```

- 正常行：`totalRow` 为空/`false`。
- 最后一行：`totalRow: true`，只有 `winAmount`/`loseAmount`，代表本次请求范围内所有行的加总（跨币种直接相加，
  和旧版 `total_win`/`total_lose` 一样是「混合」值——只有单币种查询时这个数字才有实际意义，多币种由前端按
  币种分组各自小计，见 §7）。

---

### 5. 查询设计：为什么要经过 `account_currency`

最初的实现只接受单一 `currencyId`，对每个 tenant 账号做固定的 `INNER JOIN currency`。核对旧版
`customer_report_api.php` 的 `buildReportData()` / `getAccountCurrenciesBulk()` 后发现：**账号本身有一份
「已分配币种」清单**（新 schema 里是 `account_currency` 表），报表应该按账号自己的已分配币种展开，而不是无脑
对所有账号套用同一个 currency。已改为：

```sql
FROM account a
INNER JOIN account_tenant_access ata ON ata.account_id = a.id AND ata.tenant_id = #{tenantId}
INNER JOIN account_currency ac ON ac.account_id = a.id AND ac.tenant_id = #{tenantId}
INNER JOIN currency cur ON cur.id = ac.currency_id AND cur.tenant_id = #{tenantId}
LEFT JOIN transactions t
    ON t.account_id = a.id AND t.tenant_id = #{tenantId} AND t.currency_id = ac.currency_id
   AND t.bank_process_posted_id IS NULL AND t.transaction_type IN ('WIN','LOSE')
   AND t.transaction_date BETWEEN #{dateFrom} AND #{dateTo}
```

即：`account_tenant_access` 决定「该 tenant 下有哪些账号」，`account_currency` 决定「这个账号要展示哪些
币种」，`currencyCodes` 只是在这份已分配币种清单上再做一次可选过滤。没有交易记录的 (账号, 币种) 组合仍会
出现在结果里，win/lose 都是 0，交给 Service 层的 Show All 逻辑决定要不要隐藏。

**已知限制**：旧版对「账号完全没有分配任何币种」这种边缘情况有一条 fallback（`currency=null`，把该账号所有
币种的 win/lose 混在一起显示）。新版没有实现这条 fallback——如果账号在 `account_currency` 里一条记录都没
有，它不会出现在 Customer Report 里。新 schema 下账号创建流程已经要求指定币种，预期这个边缘情况不会出现；
如果之后发现有账号确实缺 `account_currency` 记录，需要另外评估要不要补这条 fallback。

---

### 6. Service 层规则（`ReportServiceImpl`）

- Win 取正值；Lose 从 Dao 拿到的正值取负号（对齐旧版 `lose_total`「本来就是负数」的显示习惯）。
- **Show All 关闭**：只保留 Win 或 Lose 任一非 0 的 (账号, 币种) 行；**开启**：全部保留，包含 0/0。
- Total 行：对 Dao 返回的**全部**行（过滤前）分别加总 Win / Lose，再作为一笔 `totalRow=true` 的记录追加到
  列表末尾——因为被 Show All 过滤掉的行本来就是 0/0，所以「过滤前加总」和「过滤后加总」结果相同，不需要
  分开算两次。
- 不校验 `approval_status`（见 §2）。

---

### 7. 前端整合（`customerReportApi.js`）

只改了这一个文件——`CustomerReportPage.jsx` / `CustomerReportFilters.jsx` / `CustomerReportTable.jsx`
完全没动，靠 `fetchCustomerReport()` 在新旧接口之间做适配，返回值形状维持跟旧版 PHP 完全一样：

```js
{ success: true, data: [...], total_win, total_lose, date_from, date_to }
```

- `fetchCustomerReportOnce()`：单一 tenant 的 Spring 请求，把响应里 `totalRow: true` 的那一行拆出来当
  `totalWin`/`totalLose`，剩下的行映射成 Table 认得的 `{ id, account_id, name, currency, win, lose }`。
- `fetchCustomerReport()`：解析 `reportScope`（跟 Transaction Maintenance 共用同一个
  `resolveCustomerReportScope`）——`mode === "aggregate"` 时对 `mergeCompanyIds` 逐个 tenant 请求，用
  `reportAmountAdd`（高精度小数加法）把多个 tenant 的 `data` 拼接、`total_win`/`total_lose` 相加；否则
  只对 `scopeCompanyId` 单一 tenant 请求一次。
- **币种不用再前端循环**：新后端一次请求就能按 `currencyCodes` 返回多币种的行（`CustomerReportTable.jsx`
  本来就会在前端按 `currency` 字段分组、各自算小计），所以「Show All Currencies」/多选币种直接把
  `selectedCurrencies` 转成 `currencyCodes` 传给后端，不需要像 tenant 那样逐个循环。

#### 7.1 打开页面直接被踢回 Dashboard（`company_has_gambling` 字段名过期）

**现象**：点进 Customer Report，页面瞬间跳回 `/dashboard`，Network 看到一串来自 `useDashboardPage.js`
的 `get_scope_account_currencies_api.php` / `get_company_currencies_api.php` /
`user_currency_order_api.php` 500——这些 500 只是**结果**，不是原因，它们是 Dashboard 页自己的 boot
逻辑，跟 Customer Report 无关，只因为被错误重定向到 `/dashboard` 才被触发。

**根因**：`CustomerReportPage.jsx` 的 boot effect 检查 `!u.company_has_gambling` 才决定要不要跳转，
但 Spring `/auth/current-user`（`SessionUser.java`）从来没有 `company_has_gambling` 这个字段，只有
`tenant_has_game`——所以这个判断永远是 `true`，**不管用户有没有 `report` 权限都会被踢回 Dashboard**。

**修复**：改用 `utils/auth/sessionTenant.js` 里已有的 `sessionHasTenantGame(me)`（内部做了
`me?.tenant_has_game ?? me?.company_has_gambling` 的兼容判断，`bankProcessLogic`/`sessionTenant.js`
其它页面已经在用同一个 helper）。

```diff
- if (!canReport || !u.company_has_gambling) {
+ if (!canReport || !sessionHasTenantGame(u)) {
```

**同款 bug 还在 `DomainReportPage.jsx:186`**，本次没有一并修（超出 Customer Report 范围），之后要点进
Domain Report 大概率会踩到一样的重定向。

#### 7.2 Account / Currency 下拉也对齐到 Spring

`fetchAccounts()` 和 `fetchReportScopeCurrencies()` 原本打的旧 PHP 端点（`get_accounts_api.php`、
`get_scope_account_currencies_api.php`）在反向代理把所有 `/api/*` 转发给 Spring 后必然 500——跟
`docs/maintenance-navigation.md` §11.6.2 的 `fetchAccounts` 500 是同一类问题。这两个是 Customer Report
自己真正会用到的下拉数据源（不是 Dashboard 那三个无关的），本次一并对齐：

| 功能 | 旧 PHP | 新 Spring | 说明 |
|------|--------|-----------|------|
| Account 下拉 | `api/transactions/get_accounts_api.php` | `POST /api/account/list?tenant_id=` | 复用 `accountListApi.js` 的 `fetchAccountListByTenantId`（跟 Formula Maintenance 账户下拉同一个函数，见 `maintenance-navigation.md` §11.6.2）。聚合模式逐 tenant 请求，按 `id` 去重合并，`account_id` 排序。**不过滤 status**——对齐旧版 Customer Report 账户列表本来就没有 ACTIVE/INACTIVE 过滤。 |
| Currency 下拉 | `api/transactions/get_scope_account_currencies_api.php` | `POST /api/currency/list?tenant_id=` | `reportCompanyApi.js` 里改写，逐 tenant 请求后按 `code` 去重合并排序。后端目前只支持单 tenant，没有 group/scope 聚合参数（`view_group`/`group_aggregate`/`subsidiary_accounts_only` 全部丢弃）。 |

**没有动、也不需要动的**（跟 Customer Report 无关，Dashboard 自己的技术债）：

| 旧 PHP | 调用方 | 备注 |
|--------|--------|------|
| `get_company_currencies_api.php` | `reportCompanyApi.js` 的 `fetchCurrencies()` | 全仓库零调用点，死代码，没碰 |
| `user_currency_order_api.php` | `useDashboardPage.js` | 纯 Dashboard 页逻辑，Spring 端完全没有对应实现（连币种排序这个概念现在都是前端 localStorage），不在本次范围 |

---

### 8. 本地验证清单

1. 打开 Customer Report 页，Network 应看到 `POST /api/report/customer-report/list`，body 含数字
   `tenantId`、`currencyCodes` 数组（或 `null`）。
2. 单币种（非 Show All Currencies）：确认列表 + 底部 Total 数字和旧版一致。
3. 勾选 Show All Currencies 或多选币种：确认按币种分段、每段小计正确（前端 `reportAdd` 计算）。
4. 切到 Group 模式并选多个 Company 聚合：Network 应看到对应数量的 `customer-report/list` 请求（每个
   tenant 一次），列表数据是多个 tenant 结果拼接后的。
5. 开关 Show All（账号显示开关）：确认 0/0 的账号行按预期显示/隐藏。
6. 选中 `Account` 下拉里的某个账号：确认只返回该账号的行。
7. 有 `report` 权限的用户点进 Customer Report **不应该**被弹回 `/dashboard`。
8. Account / Currency 下拉都能正常列出选项，Network 分别看到 `POST /api/account/list` 和
   `POST /api/currency/list`，不再出现 `get_accounts_api.php` / `get_scope_account_currencies_api.php`。

---

### 9. 变更文件清单（2026-08-13）

**后端**

| 文件 | 说明 |
|------|------|
| `dao/ReportDao.java` | `findCustomerReportRows` |
| `dto/CustomerReportDTO.java` | 请求 + 响应行合一 |
| `service/ReportService.java` / `service/impl/ReportServiceImpl.java` | Show All / Lose 取负 / Total 合成 |
| `controller/ReportController.java` | `POST /api/report/customer-report/list` |
| `resources/mybatis/ReportMapper.xml` | `account` × `account_currency` LEFT JOIN `transactions` |

**前端**

| 文件 | 说明 |
|------|------|
| `pages/report/customer/customerReportApi.js` | `fetchCustomerReport` 改走 Spring；`fetchAccounts` 改走 `POST /api/account/list`（见 §7.2） |
| `pages/report/shared/reportCompanyApi.js` | `fetchReportScopeCurrencies` 改走 `POST /api/currency/list`（见 §7.2） |
| `pages/report/customer/CustomerReportPage.jsx` | 修复 `company_has_gambling` 过期字段名导致的 Dashboard 误跳转（见 §7.1） |

---

### 10. 维护约定

- 新增字段时：先改 `CustomerReportDTO` + 本文，再改 `normalizeSpringCustomerReportRow`。
- 币种口径、tenant 循环写法与 [`process-list-spring-api.md`](./process-list-spring-api.md) §5、
  `maintenance-navigation.md` 保持一致；DATA CAPTURE 判定口径与
  [`transaction-datacapture-winloss.md`](./transaction-datacapture-winloss.md) 保持一致——两边任一处改动
  判定条件，另一处要同步检查。

---

### 11. 2026-08-18 补充：找回被覆盖的迁移 + 清掉 Bank-only 检测的最后一个 PHP 调用

`customerReportApi.js` / `reportCompanyApi.js` 在本次迁移（2026-08-13，`6d7801b`）之后，被同一天晚些时候
的大批量提交 `4f00f14`（"new version frontend...already change account/admin/transaction page to
springboot api"，316 个文件的整仓快照式覆盖）意外整体回退回了纯 PHP 版本——`git log` 上看得到 `6d7801b`
之后紧跟 `4f00f14` 把这两个文件的内容整段替换回旧的 `customer_report_api.php` / `get_accounts_api.php`
写法。`CustomerReportPage.jsx` 本身没有回退（`4f00f14` 之后又被继续加了 Group/Company pill、
currency 跨页同步、snapshot cache 等新功能），所以页面代码一直是按 Spring 版本的 `fetchCustomerReport` /
`fetchAccounts` 参数和返回值形状在调用——只是背后的实现被换回了会 500 的 PHP 端点。

本次把 `customerReportApi.js` / `reportCompanyApi.js` 按 `6d7801b` 的实现重新对齐（`fetchReportScopeCurrencies`
改用 `utils/api/currencyApi.js` 的 `fetchCurrencyListByTenantId`，与 Account/Currency 设置页共用同一个
helper，而不是各自手写一份 fetch）；`reportCompanyApi.js` 里死代码 `fetchCurrencies`（`get_company_currencies_api.php`，
零调用点）一并删除。

顺带修掉一个页面自己的 PHP 依赖：`CustomerReportPage.jsx` 的 `checkBankOnly`（切换 Company 后判断是不是
Bank-only、要不要跳去 Bank Process List）原本调用 `reportCompanyApi.js` 的 `fetchCompanyPermissions`，打的
是 `api/domain/domain_api.php`——同样会被反向代理 500，导致 catch 静默吞掉，Bank-only 检测实际上从没生效过。
改成跟 `utils/auth/sidebarPermissions.js` 的 `canShowReportInSidebar` 同一套判定：`companyMatchesBankOnlyPillScope`
(`utils/company/companyCategoryFlags.js`)，纯前端根据已加载的 `companies` 行 / session flags 缓存判断，
不用再发请求。`fetchCompanyPermissions` / `isBankOnlyCategoryCompany` 在 `reportCompanyApi.js` 里也一并删除。

**教训**：以后做大范围「整仓快照替换」式的提交前，先确认要不要 `git diff` 一下当天更早的迁移提交，避免
覆盖掉刚做完的工作。

#### 11.1 前端改动文件清单（2026-08-18）

| 文件 | 改动 |
|------|------|
| `pages/report/customer/customerReportApi.js` | `fetchCustomerReport` / `fetchAccounts` 从纯 PHP 实现重新改回 Spring：`POST /api/report/customer-report/list`（tenant 循环聚合、拆分 `totalRow` 行）+ `fetchAccountListByTenantId`（`POST /api/account/list`）。不再触碰 `customer_report_api.php` / `get_accounts_api.php`。 |
| `pages/report/shared/reportCompanyApi.js` | `fetchReportScopeCurrencies` 改用 `utils/api/currencyApi.js` 的 `fetchCurrencyListByTenantId`（`POST /api/currency/list`），tenant 循环 + 按 code 去重合并。删除死代码 `fetchCurrencies`（`get_company_currencies_api.php`，零调用点）、删除 `fetchCompanyPermissions` / `isBankOnlyCategoryCompany`（原打 `api/domain/domain_api.php`，见下）。 |
| `pages/report/customer/CustomerReportPage.jsx` | `checkBankOnly` 不再调用 `reportCompanyApi.js` 的 `fetchCompanyPermissions`（PHP，一直在静默 500，判定从未生效），改成纯前端 `companyMatchesBankOnlyPillScope`（`utils/company/companyCategoryFlags.js`），基于已加载的 `companies` 行 / session flags 缓存判断，无需额外请求。 |

同一次事故也影响了 Domain Report，改动清单见
[`domain-report-spring-migration.md` §10.1](./domain-report-spring-migration.md#101-前端改动文件清单2026-08-18)。

---

## 30. Domain Report — Spring API 迁移说明

> 原始独立文件：`docs/domain-report-spring-migration.md`（内容已合并于此；原文件已改为跳转说明）


> **前端仓库**：`../Count-frontend/`
> **后端前缀**：`/api/report/*`
> **最后更新**：2026-08-13

---

### 1. 背景

Domain Report 原本完全跑在旧版 PHP（`count168/api/reports/domain_report_api.php`），以 `process` 为主表
（无数据的 process 也显示 0），直接聚合旧表 `data_capture_details.processed_amount`——跟遷移前的
`customer_report_api.php` 是同一種寫法。

跟 [`customer-report-spring-migration.md`](./customer-report-spring-migration.md) 一样，本次把
Win/Lose 的資料來源换成 `transactions` 表（同一套 DATA CAPTURE 判定口径），不再依赖已淘汰的
`data_capture_details`。

---

### 2. 跟 Customer Report 的關鍵差異

| | Customer Report | Domain Report |
|---|---|---|
| 分組維度 | Account | **Process**（GAME 類別，透過 `data_captures.process_id` 接上） |
| Lose 顯示 | 取負 | **維持正值，不取負** |
| 第四欄 | 無 | **Win/Lose = Win − Lose**（餘額核對值，理論上接近 0） |
| Turnover | 無 | **Turnover = Win + Lose** |
| Show All | 有開關 | **沒有**，process 永遠全部顯示（含 0/0） |
| Currency 維度 | 有（`account_currency` 多幣種） | **沒有** |
| Category 過濾 | 不適用 | **只看 `process.category = 'GAME'`**（Bank Process 完全不算在內，這點是新 schema 才有的規則，legacy `process` 表根本沒有 category 欄位） |

---

### 3. Spring 端點

| 能力 | 方法 | 路徑 | Body |
|------|------|------|------|
| Domain Report 列表 | POST | `/api/report/domain-report/list` | `DomainReportDTO`（見 §4） |

成功響應：`{ "success": true, "message": "...", "data": [...] }`（跟 `ReportController` 其他端點同款）。

---

### 4. 請求 / 響應結構：`DomainReportDTO`

同一個類既是請求體也是響應行（跟 `CustomerReportDTO` 同一種複用風格）。

**請求字段**

```json
{ "tenantId": 42, "dateFrom": "2026-01-01", "dateTo": "2026-08-13", "processId": null }
```

| 字段 | 說明 |
|------|------|
| `tenantId` | 必填，> 0 |
| `dateFrom` / `dateTo` | 必填 |
| `processId` | 可選；`null`/`0` = All Process |

**響應行**（`data` 數組，最後一筆是合成的 Total 行）

```json
[
  { "processRowId": 7, "processCode": "25C01", "description": "28WIN", "turnoverAmount": 33189.00, "winAmount": 16594.49, "loseAmount": 16594.51, "winLoseAmount": -0.02 },
  { "totalRow": true, "turnoverAmount": 1234567.89, "winAmount": 617283.94, "loseAmount": 617283.95, "winLoseAmount": -0.01 }
]
```

---

### 5. 查詢設計

`process` 當主表（driving table，`p.category = 'GAME'`），逐層 LEFT JOIN 出去：

```sql
FROM process p
LEFT JOIN data_captures dc
    ON dc.process_id = p.id AND dc.tenant_id = p.tenant_id
   AND dc.capture_date BETWEEN #{dateFrom} AND #{dateTo}
LEFT JOIN data_capture_line dl ON dl.capture_id = dc.id AND dl.tenant_id = p.tenant_id
LEFT JOIN transactions t
    ON t.id = dl.transaction_id AND t.tenant_id = p.tenant_id
   AND t.bank_process_posted_id IS NULL AND t.transaction_type IN ('WIN','LOSE')
WHERE p.tenant_id = #{tenantId} AND p.category = 'GAME'
```

- **Description 組合**：新 schema 的 `process` 已經沒有 `description_id` 純量欄位了（legacy 是 1:1），
  變成 `process_description_link` 多對多。用相關子查詢
  `GROUP_CONCAT(DISTINCT pd.name ORDER BY pd.name SEPARATOR ', ')` 組出來。實務上如果每個 process 都只
  掛一個 description，這個子查詢的結果就跟 legacy 的 `"code (description)"` label 一模一樣；如果之後
  發現有 process 掛了多個 description，畫面上會看到用逗號串起來的多個名稱，這是預期行為，不是 bug。
- **Service 層計算** `turnoverAmount = win + lose`、`winLoseAmount = win − lose`（Lose 不取負，直接相加
  相減即可）。
- 沒有 Show All 過濾——所有 GAME process 一律列出，包含 0/0。
- Total 行：對 Dao 返回的**全部**行分別加總後 append 到 list 最後，`totalRow: true`。

---

### 6. 前端整合（`domainReportApi.js`）

只改了這一個文件 + `DomainReportPage.jsx` 的一行 bug 修復——`DomainReportFilters.jsx` /
`DomainReportTable.jsx` 完全沒動，回傳值形狀維持跟舊版 PHP 完全一樣：

```js
{ success: true, data: [{process, description, turnover, win, lose, win_lose}], totals: {turnover, win, lose, win_lose}, date_from, date_to }
```

#### 6.1 一個重要的範圍界定：Group-only（AP/IG payroll）沒有遷移

Domain Report 的 Process 篩選框在「純 Group 模式」（`selectedGroup` 有值、但沒選 Company）下，UI 邏輯
（`domainReportUsesSalaryBonusProcesses`，`scope.mode === "group"`）會切換成只顯示固定的
**SALARY / COMMISSION / BONUS** 三個 payroll process。這幾個 process 是 **BANK 類別**，不是 GAME——
跟 Customer/Domain Report 設計時談好的「只看 GAME process win/lose」是完全不同的資料領域（比較像是薪資/
佣金這類 Bank Capture 流程,不是 Win/Lose 報表本體）。

新的 Spring Domain Report 端點目前**只查 `category = 'GAME'`**，不會回傳 SALARY/COMMISSION/BONUS
這幾個 BANK process 的資料，所以：

- `fetchDomainReport()` / `fetchProcesses()` 在 `reportScope.mode === "group"` 時，**維持呼叫原本的舊
  PHP**（`fetchDomainReportLegacy` / `fetchProcessesLegacy`，程式碼原封不動搬過去），不做任何遷移。
- 只有 **Company 模式** 跟 **Group 內選了 Company（aggregate）模式** 這兩種——也就是畫面上實際看 GAME
  process win/lose 報表的主要場景——才走新的 Spring 端點。

**已知限制**：純 Group 模式（不選 Company）本來就會撞到 `get_scope_account_currencies_api.php` 那一類
「反向代理把 `/api/*` 轉發給 Spring、legacy PHP 路由 500」的問題（跟 Customer Report §7.2 提過的是同一
個技術債），這個限制不是本次遷移造成的，也不在這次範圍內處理。如果之後要做 SALARY/COMMISSION/BONUS 的
Domain Report，需要另外設計（BANK category 的資料來源、Bank Capture 的 win/lose 定義都跟 GAME 不一樣）。

#### 6.2 Process 下拉（Company / Aggregate 模式）

複用 Games Process List 已經在用的 `POST /api/process/process-list`（`processListApi.js` 的
`fetchProcessListByTenantId`，已經會過濾掉 BANK 類別）。聚合模式逐 tenant 請求、按 `id` 去重合併，
`process` 排序。回傳的每個選項是 `{id, process, description, display_text}`，`display_text` 組法跟
legacy 一致：`description` 有值時是 `"CODE (DESCRIPTION)"`，否則就是 `CODE`。

#### 6.3 `company_has_gambling` 過期字段名 bug

跟 [`customer-report-spring-migration.md` §7.1](./customer-report-spring-migration.md) 提到的是同一個
bug，`DomainReportPage.jsx:186` 一路留到現在才修。改用 `sessionHasTenantGame(u)`
（`utils/auth/sessionTenant.js`）。

---

### 7. 本地驗證清單

1. Company 模式打開 Domain Report，Network 應看到 `POST /api/report/domain-report/list`，body 含數字
   `tenantId`。
2. 確認 Turnover/Win/Lose/Win-Lose 四欄數字，Win-Lose 應該接近 0；Total 行數字正確。
3. Process 下拉能列出當前 tenant 全部 GAME process，選中特定 process 後只返回該筆。
4. 切到 Group 模式並選多個 Company 聚合：Network 應看到對應數量的 `domain-report/list` 請求（每個
   tenant 一次）。
5. 切到純 Group（不選 Company）模式：Process 下拉應該只顯示 SALARY/COMMISSION/BONUS，Network 走的是
   舊版 `domain_report_api.php`（不是 Spring 端點）——這是預期行為，不是遺漏。
6. 有 `report` 權限的用戶點進 Domain Report **不應該**被彈回 `/dashboard`。

---

### 8. 變更文件清單（2026-08-13）

**後端**

| 文件 | 說明 |
|------|------|
| `dto/DomainReportDTO.java` | 請求 + 響應行合一 |
| `dao/ReportDao.java` | `findDomainReportRows` |
| `resources/mybatis/ReportMapper.xml` | `process`（`category='GAME'`）LEFT JOIN `data_captures` → `data_capture_line` → `transactions` |
| `service/ReportService.java` / `service/impl/ReportServiceImpl.java` | Turnover/Win-Lose 計算，無 Show All |
| `controller/ReportController.java` | `POST /api/report/domain-report/list` |

**前端**

| 文件 | 說明 |
|------|------|
| `pages/report/domain/domainReportApi.js` | `fetchDomainReport`/`fetchProcesses` 改走 Spring（Company/Aggregate 模式），Group-only 模式維持舊版 PHP（見 §6.1） |
| `pages/report/domain/DomainReportPage.jsx` | 修復 `company_has_gambling` 過期字段名（見 §6.3） |

---

### 9. 維護約定

- 新增字段時：先改 `DomainReportDTO` + 本文，再改 `normalizeSpringDomainReportRow`。
- DATA CAPTURE 判定口徑、`transactions` join 方式與
  [`customer-report-spring-migration.md`](./customer-report-spring-migration.md) 保持一致，任一處改動
  判定條件，另一處要同步檢查。
- 如果之後要幫 Group-only（SALARY/COMMISSION/BONUS）補上 Spring 端點，需要另外評估 BANK category 的
  win/lose 資料來源，不能直接套用現有 `category = 'GAME'` 的 query。

---

### 10. 2026-08-18 補充：找回被覆蓋的遷移

跟 [`customer-report-spring-migration.md` §11](./customer-report-spring-migration.md#11-2026-08-18-补充找回被覆盖的迁移--清掉-bank-only-检测的最后一个-php-调用)
是同一次事故：`domainReportApi.js` 在遷移完成（`6d7801b`）當天稍晚被整倉快照式提交 `4f00f14` 整段覆蓋回純
PHP 版本，Company/Aggregate 模式的 `fetchDomainReport` / `fetchProcesses` 一路在打會 500 的
`domain_report_api.php`。`captureMaintenanceLogic.js` 的 Group-only payroll process 下拉也依賴這個檔案的
`fetchProcesses` 導出，所以連帶受影響。

本次按 `6d7801b` 的實現重新對齊 `domainReportApi.js`（Company/Aggregate 走 Spring
`api/report/domain-report/list` + `api/process/process-list`，Group-only 維持舊版 PHP，見 §6.1，行為未變）；
同時把 `DomainReportPage.jsx` 的 `checkBankOnly` 從打 `api/domain/domain_api.php` 的
`fetchCompanyPermissions` 改成前端純判定的 `companyMatchesBankOnlyPillScope`
(`utils/company/companyCategoryFlags.js`)，理由同 Customer Report §11。

#### 10.1 前端改動文件清單（2026-08-18）

| 文件 | 改動 |
|------|------|
| `pages/report/domain/domainReportApi.js` | `fetchDomainReport` / `fetchProcesses` 從純 PHP 實現重新改回 Spring：Company/Aggregate 走 `POST /api/report/domain-report/list`（tenant 循環聚合、拆分 `totalRow` 行）+ `fetchProcessListByTenantId`（`POST /api/process/process-list`）；Group-only（SALARY/COMMISSION/BONUS）維持 `fetchDomainReportLegacy` / `fetchProcessesLegacy` 打舊版 `domain_report_api.php`，行為與 §6.1 一致、未變動。此檔案同時被 `captureMaintenanceLogic.js` 的 Group-only payroll process 下拉引用，一併修復。 |
| `pages/report/domain/DomainReportPage.jsx` | `checkBankOnly` 不再調用 `api/domain/domain_api.php`（PHP，一直在靜默 500，判定從未生效），改成純前端 `companyMatchesBankOnlyPillScope`（`utils/company/companyCategoryFlags.js`）。 |

Customer Report 那邊的 `reportCompanyApi.js` / `CustomerReportPage.jsx` 改動清單見
[`customer-report-spring-migration.md` §11.1](./customer-report-spring-migration.md#111-前端改动文件清单2026-08-18)。

---

## 31. Accounting Due Frequency 业务规则

> 原始独立文件：`docs/accounting-due-frequency-rules.md`（内容已合并于此；原文件已改为跳转说明）


本文档记录 Bank Process 在 Accounting Due 中的出账规则。修改 Frequency、账期生成、跳过或交易逻辑时，必须同步更新本文档。

### 通用规则

- `ACTIVE`：一律可生成正常账单，不分合同类型。
- `OFFICIAL`、`E_INVOICE`、`BLOCK`：**非 1+N 合同**可正常生成账单（各 frequency 原规则）；**1+N 合同**不生成正常账单，改走赔款（见下方「Contract 1+1 / 1+2 / 1+3（赔款，已实现）」章节）。
- `INACTIVE`、`WAITING`：不生成任何账单，也不触发赔款。
- `postedDate` 是账单锚点，也是账期唯一键的一部分。
- 已 `POSTED` 或 `SKIPPED` 的账期通过 `bankProcessId + postedDate + periodType` 排除。
- Accounting Due 只返回尚未结算的账期。
- **非当月跳过（对照月 = 合同 `createdAt` 所在自然月）**：`dayStart` 早于创建月时，创建月之前的账期不出；只从创建月起（及之后）展示。例：7 月创建、`dayStart` 在 6 月 → 跳过 6 月，只出 7 月及往后。
- **合约到期（过 `dayEnd`）后是否继续出账（已实现）**：
  - `ACTIVE`：合约到期**不停止**出账，`Monthly` / `Week` / `Day` 持续按原周期无限期生成，直到手动把 status 切成 `INACTIVE` 才立即停止（Accounting Due 全程即时计算，一旦 status 变更，下次读取立刻反映，不需要额外清理）。`1st of Every Month` 见下方特例。
  - `OFFICIAL` / `E_INVOICE` / `BLOCK`（非 1+N 合同）：合约到期**照常停止**，不套用上述延伸，行为与旧版 `ACTIVE` 一致（到 `dayEnd` 所在月 / 周期为止）。
  - `Week`、`Day` 本来就不需要 `dayEnd`（合约无到期概念），因此这两个 frequency 不受此规则影响，一直以来都是持续出账直到手动切 `INACTIVE`。
- **建立时已完全过期（`expired_at_creation`，已实现，仅 `FIRST_OF_EVERY_MONTH` / `MONTHLY`）**：
  - Insert 当下（不是 Update）判断一次：`dayEnd` 所在月份若早于建立当月（`bank_process.created_at` 所在自然月），代表这笔合同从一开始就是补登的历史合同，不是随时间自然走到期的，写入 `bank_process.expired_at_creation = 1`；之后编辑（Update）不会重新计算，终身维持建立时的原值。
  - `expired_at_creation = 1` 时，即使 `status = ACTIVE`，也**不**套用上面「到期后继续延伸」的规则，改为在 `dayEnd` 处封顶：`1st of Every Month` 强制视同 Day end 开关 `ON`（走 `DAY_END_TAIL`）；`Monthly` 强制视同到 `dayEnd` 即停（同 `OFFICIAL`/`E_INVOICE`/`BLOCK` 的行为）。
  - `expired_at_creation = 0`（默认）的合同行为不变。

### 1st of Every Month

- 需要 `dayStart`、`dayEnd`，按自然月出账。
- 首月从 `dayStart` 开始：
  - `dayStart` 为当月 1 日：`FIRST_MONTH`。
  - `dayStart` 非当月 1 日：`PARTIAL_FIRST_MONTH`。
- 中间完整月份：`FULL_MONTH`，账期为当月 1 日至月末。
- 最后一个月若 `dayEnd` 早于月末：
  - Day end 开关 **ON**（`dayEndMonthlyCapEnabled=true`）**或** `expired_at_creation=1`（建立时已完全过期，见上方通用规则）→ `DAY_END_TAIL`，账期 `[1st, dayEnd]`（例：9/1–9/9）；此时 **不延伸**，出到 `dayEnd` 所在月即止，`ACTIVE` 与 `OFFICIAL`/`E_INVOICE`/`BLOCK` 行为一致。
  - Day end 开关 **OFF** → 仍走 `FULL_MONTH`，账期 `[1st, 月末]`（例：9/1–9/30）。
    - **`ACTIVE`**：过了 `dayEnd` 所在月之后**继续**逐月生成 `FULL_MONTH`，无限期出到 `today` 所在月，直到手动切 `INACTIVE`。
    - **`OFFICIAL` / `E_INVOICE` / `BLOCK`（非 1+N）**：仍在 `dayEnd` 所在月停止，不延伸。
- 首月 `postedDate = dayStart`，之后月份 `postedDate = 当月 1 日`。
- 返回 **所有** `postedDate <= today`、且落在合约月内（`ACTIVE` + Day end 关闭时不受合约月上限约束）、**不早于创建月** 的账期（可多笔并列）。
- **非当月跳过**：7 月创建 + `dayStart` 在 6 月 → 跳过 6 月账单，从 7 月起算。
- **未提交保留**：7 月未提交时，到 8 月仍保留 7 月；若 8 月已到 posted day，同时出现 8 月。
- **Delete（Skip）**：删掉某月后该月不再显示；到了下月照常显示已到期的下月。
- **Refresh（restoreSkipped）**：恢复被删月份，不影响其它已在 Due 的月份。
- Edit Process（仅 1st of every month）Day end 旁开关会持久化到 `bank_process.day_end_monthly_cap_enabled`。

#### Post to Transaction（1st of Every Month，已实现）

- API：`POST /api/bank-process/accounting-due/post`，body 与 skip 同形：`[{ bankProcessId, postedDate, periodType, billingStart, billingEnd }]`。
- 仅支持 `FIRST_OF_EVERY_MONTH` 的账期类型：`FIRST_MONTH` / `PARTIAL_FIRST_MONTH` / `FULL_MONTH` / `DAY_END_TAIL`。
- 金额来源：
  - **Buy Price**（必拿）→ Supplier，`WIN`
  - **Sell Price**（必拿）→ Customer，`LOSE`
  - **Profit**（必拿，存的是净毛利）→ Company，`WIN`（允许 `0.00`）
  - **Profit Sharing**（可选）→ 各分账账户，`WIN`；无 shares 则跳过
- 比例算法：
  - `FULL_MONTH` / `FIRST_MONTH`：比例 = 1（全额）
  - `PARTIAL_FIRST_MONTH` / `DAY_END_TAIL`：比例 = 闭区间天数 / 该自然月总天数  
    例：`7/15–7/31` → 17/31；尾段 `9/1–9/9` → 9/30
- Buy / Sell / Profit / PS（若有）共用同一比例；进账金额按 [transaction-amount-precision.md](./transaction-amount-precision.md)：普通交易最多 **6** 位小数、**不** round-to-2；系统折算仅当结果超过 6 位时才 HALF_UP 到 6 位。API / 库为真值，UI 展示再 round 2。
- 写入顺序：先 `bank_process_accounting_posted`（`outcome=POSTED`）→ 再写 N 条 `transactions`（共用 `bank_process_posted_id`）。
- `transaction_date` = 该行 `postedDate`；审批一律 `APPROVED`。
- Description（银行名 = Bank Name）：
  - `FULL_MONTH` / `FIRST_MONTH`：`FULL MONTH (MAY 2026) @MONTHLY 200 | RHB`——`200` = 该账户原价（ratio = 1，跟实际进账金额相同）。
  - `PARTIAL_FIRST_MONTH` / `DAY_END_TAIL`：`PRORATED(15/7 - 31/7 | 17 DAYS)@MONTHLY 3200 | RHB`（`d/M` 闭区间 + 天数）——**`3200` 是该账户的原价（未按比例换算）**，不是这 17 天实际进账的金额（实际进账金额 = 原价 × 比例，仍照常写入 `transactions.amount` / 显示在列表 WIN/LOSS 栏）。`@MONTHLY` 是给读者的参照基准（"这是月费原价"），实际进账多少要看交易金额栏，不能直接从 description 读出来。
  - 1st 进账固定写 `@MONTHLY`
- 已 `POSTED` / `SKIPPED` 的账期拒绝重复入账。

#### Contract 1+1 / 1+2 / 1+3（赔款，已实现）

- 仅 Contract 值为 `1+1` / `1+2` / `1+3`（租期按 **1 个月**；前端 Day end 也只按 1 个月算）。
- **ACTIVE**：正常出账 / 进账仍按各 frequency 原规则（1st 仍可有 Partial First Month 等），金额不因 +N 放大。
- **OFFICIAL / E_INVOICE / BLOCK**：进入这三个状态中任一个时，**无条件立即**走赔款——不看之前是否已出过正常账、不看合约是否仍在有效期内，忽略正常账单排程，直接生成一笔赔款账期。
  - 倍数：1+1 → ×1；1+2 → ×2；1+3 → ×3（Buy / Sell / Profit / 可选 PS）。
  - `postedDate = billingStart = billingEnd = dayStart`；Post 时 `transaction_date = today`。
  - Description：`COMPENSATION ONE|TWO|THREE MONTH {amt} | {bank}`。
  - 赔款只生成一次：已 Post 过赔款（`countCompensationTransactions > 0`）后，Inbox 不再重复列出，自动结清该 slot。
- **INACTIVE**：不生成任何账单，也**不**触发赔款。
- 不做：延长 `day_end`、因赔款改 status。
- 实现：`BankAccountingDueServiceImpl` 的 `COMPENSATION_ELIGIBLE_STATUS = {OFFICIAL, E_INVOICE, BLOCK}`；`isBillableForDueGeneration` / `isPostAllowed` 让这三个状态 + 1+N 合同**不**进入正常 frequency 出账路径，只由 `resolveOnePlusCompensationDue` 生成赔款；三个状态 + 非 1+N 合同则维持正常出账（见上方通用规则）。

### Monthly

- 需要 `dayStart`、`dayEnd`。
- 以 `dayStart` 为首个 posted 锚点，后续月份使用月度锚点（`dayStart` 日 − 1，见 `monthlyAnchor`）：
  - `dayStart` 非 1 号：锚点固定在每月「`dayStart` 日 − 1」（例：`dayStart=5/20` → 之后每月 19 号）。
  - `dayStart` 为 1 号（"日 0"）：借回上一个月的月底，**跟着当月实际天数走（28/29/30/31）**，不是固定日期（例：`dayStart=8/1` → 第 2 期 `8/31`、第 3 期 `9/30`、第 4 期 `10/31`……）。
- `billingStart = postedDate`，`billingEnd = postedDate + 1 个月`。
- **`OFFICIAL` / `E_INVOICE` / `BLOCK`（非 1+N）**：最后一期 posted 锚点为 `dayEnd`；`periodType = MONTHLY`，到期即停。
- **`ACTIVE` 且 `expired_at_creation=0`**：锚点不再 clamp 到 `dayEnd`，也不在到期月停止，持续按月滚动无限期生成，直到手动切 `INACTIVE`。
- **`ACTIVE` 且 `expired_at_creation=1`**（建立时已完全过期，见上方通用规则）：不套用上一条的无限期延伸，行为等同 `OFFICIAL`/`E_INVOICE`/`BLOCK`——到 `dayEnd` 即停。
- 返回 **所有** `postedDate <= today`、**不早于创建月** 的锚点账期（可多笔并列）。
- **非当月跳过**：与 1st 相同（创建月之前的锚点不出）。
- **未提交保留 / Delete / Refresh** 与 1st of Every Month 相同。

#### Post to Transaction（Monthly，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `MONTHLY` frequency + `periodType = MONTHLY`。
- 金额：**每期全额**（比例恒为 1）；无 partial、无 day end tail、无按天数折算。
- 金额来源与写入规则同 1st 进账：Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`。
- Description：`MONTHLY BILL 400 | MARI`（金额 = 该行金额；银行名 = Bank Name）。

### Once

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`。
- 用于一次性合同付款。
- `postedDate = billingStart = billingEnd = dayStart`，`periodType = ONCE_ONE_OFF`。
- `today >= dayStart` 时生成；未到 dayStart 则等待。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月）：
  - `dayStart` 早于创建月 → 不进 Due，并自动改为 `INACTIVE`。
  - 例：7 月创建、`dayStart` 在 6 月 → 直接跳过。
- **已生成未执行保留**：一旦进入 Due，跨到新自然月后仍保留，直到用户提交或手动 Skip（不再用 today 所在月过滤）。
- 每个 process 最多返回一笔账单。
- Once 在 `POSTED` 或用户手动 `SKIPPED` 后自动改为 `INACTIVE`（停止再出 Due）。

#### Post to Transaction（Once，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `ONCE` frequency + `periodType = ONCE_ONE_OFF`。
- 金额：**全额**（比例恒为 1）；无 partial / 按天数折算。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（= `dayStart`）。
- Description：`ONCE (20/07/2026) @ 1000 | PBB`（`dd/MM/yyyy` + 本行金额 + Bank Name）。
- 入账成功后将该 process `status` 改为 `INACTIVE`。

### Week

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`。
- `dayStart` 是永久周账期锚点；用户手动改为 `INACTIVE` 时立即停止出账。
- 每周一期为互不重叠的 7 天（含首尾）：`billingStart` 至 `billingStart + 6 天`。
- 下一期从上一期结束日的次日开始：`nextStart = billingEnd + 1 天`。
- `postedDate = billingStart`，`periodType = WEEKLY`。
- 例：`dayStart = 2026-06-25`：
  - `06-25 – 07-01`，posted `06-25`
  - `07-02 – 07-08`，posted `07-02`
  - `07-09 – 07-15`，posted `07-09`
  - `07-16 – 07-22`，posted `07-16`
  - `07-23 – 07-29`，posted `07-23`
- 只有 `postedDate <= today` 的账期可以出现，未来账期等待。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月，不是 today 所在月）：
  - 仅当 `dayStart` 早于创建月时生效。
  - 完全落在创建月之前的周账期不出。
  - 跨入创建月的周账期仍出（如 7 月创建、`6/25–7/1`）。
- **已生成未执行保留**：创建月及之后已到期、未 POSTED/SKIPPED 的周账期，跨到新自然月后仍留在 Due，直到用户提交或手动 Skip（不会因换月被过滤掉）。
- 一个 Week process 可以同时返回多笔已到期、未结算的账单。

> 周账期为互不重叠的闭区间 `[billingStart, billingEnd]`（共 7 天），下一期从 `billingEnd + 1` 开始。前端 Billing Date 显示完整的 `start – end` 区间。

#### Post to Transaction（Week，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `WEEK` frequency + `periodType = WEEKLY`。
- 金额：**每期全额**（比例恒为 1）；无 partial / 按天数折算。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（= `billingStart`）。
- Description：`WEEK (01/06/2026 - 07/06/2026) @ 100 | PBB`（`dd/MM/yyyy` 闭区间 + 本行金额 + Bank Name）。

### Day

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`（与 Week 对齐）。
- `dayStart` 为每日账单锚点；用户手动改为 `INACTIVE` 时立即停止出账。
- 每一天一笔账单：`postedDate = billingStart = billingEnd = 该日`，`periodType = DAILY`。
- 生成范围：从 `max(dayStart, 合同创建月 1 日)` 到 `today`（含）。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月）：
  - 创建月之前的日期一律不出（Day 无跨月踏入例外）。
- **已生成未执行保留**：已到期未结算的日账单跨月后仍留在 Due，直到用户提交或手动 Skip。
- 当月内尚未到期的未来日等待，不提前列出。
- 例：`dayStart = 2026-07-01`，今天 `2026-07-17` → Due 并列 `07-01` … `07-17`；进 8 月若未提交则这些行继续存在。
- 例：7 月创建、`dayStart = 2026-06-15` → 只从 7 月 1 日起出；6 月日不出。
- 一个 Day process 可以同时返回多笔已到期、未结算的账单。

#### Post to Transaction（Day，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `DAY` frequency + `periodType = DAILY`。
- 金额：**每期全额**（比例恒为 1）。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（单日）。
- Description：`DAY (10/06/2026) @ 22 | PBB`（`dd/MM/yyyy` + 本行金额 + Bank Name）。

### 单笔与多笔返回

| Frequency | 一个 process 在 Accounting Due 中的返回数量 |
| --- | --- |
| 1st of Every Month | 可同时多笔（所有已到期未结算月） |
| Monthly | 可同时多笔（所有已到期未结算锚点） |
| Once | 最多 1 笔 |
| Week | 可同时多笔 |
| Day | 可同时多笔 |

### 前后端约定

- 后端 Frequency：`FIRST_OF_EVERY_MONTH`、`MONTHLY`、`ONCE`、`WEEK`、`DAY`。
- 前端值：`1st_of_every_month`、`monthly`、`once`、`week`、`day`。
- Once / Week / Day 表单禁用并不提交 `dayEnd`、`contract`。
- 前端 Accounting Due 行键必须包含 process、period type 和 posted date，确保多账期（含 1st / Monthly / Week / Day）可独立选择、Skip 与 Refresh 恢复。
- Day（`DAILY`）Billing Date 展示单日；Week（`WEEKLY`）展示 `start – end` 区间。
- Resend（`RESEND_CONSOLIDATED`）：Week / Monthly / 1st 补单 Billing Date 同样展示 `start – end`；Once / Day 补单展示单日。

### Resend（补单）

Resend 在正常 Accounting Due **之外**追加一笔 make-up 账单。不修改合同 `dayStart` / `dayEnd` / `frequency`，不删除正常 ledger，不影响正常出账。

实现隔离：`BankProcessResendService` / `BankProcessResendController` / `BankProcessResendDao`（与 CRUD、Accounting Due 分离）。

#### 通用（Phase 1）

- API：`POST /api/bank-process/resend`，body 使用 `AccountingDueDTO`：`tenantId`、`bankProcessId`、`dayStart`、`dayEnd`、`frequency`。
- 仅 `ACTIVE` 可 Resend。
- 成功后写入 `bank_process.resend_schedule_*`（每个 process 最多一笔开放补单）。
- Inbox 追加：`periodType = RESEND_CONSOLIDATED`。
- **开放补单一律展示**：不因 `today` / `asOf`、未到期、创建月门槛等过滤；过去或未来 `dayStart` 只要 Resend 成功都会出现在 Accounting Due。
- **同 process + 同 `dayStart`** 且补单仍在 Due（未 Post/Skip）→ 拒绝。
- **同 process + 不同 `dayStart`** → 允许，**覆盖**旧开放补单（只保留最新）。
- **不同 process** → 互不影响。
- Skip 该 `RESEND_CONSOLIDATED` 时清除 `resend_schedule_*`；再次同锚点 Resend 会清除此前 `SKIPPED` make-up ledger，**允许同 dayStart + 同 frequency 再补一次**。
- **尚未实现**：Post 同日锁、Maintenance 清锁。

#### Post to Transaction（Resend，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`；识别 `periodType = RESEND_CONSOLIDATED`。
- 账期窗口 = Due 上用户补单的 `billingStart` / `billingEnd` / `postedDate`（不按合同滚动锚点重算）。
- Frequency 优先用开放补单的 `resend_schedule_frequency`，否则用 process frequency。
- 金额来源与写入顺序同正常 Post：Buy / Sell / Profit 必拿，PS 可选；ledger `POSTED` → N 条 `transactions`；`transaction_date = postedDate`。
- Post 成功后清除 `resend_schedule_*`（与 Skip 相同）。
- **不做赔款**；**Once 补单 Post / Skip 都不改** process status（补的是正常账单，不是惩罚金）。
- 已 `POSTED` 的同键拒绝重复进账；Skip 后再 Resend 同锚点见上文。

| Frequency | 金额 | Description |
|-----------|------|-------------|
| Monthly | 全额 | `MONTHLY BILL {amt} \| {bank}` |
| Week | 全额 | `WEEK (start - end) @ {amt} \| {bank}`（用户那一周） |
| Day | 全额 | `DAY (单日) @ {amt} \| {bank}` |
| Once | 全额 | `ONCE (单日) @ {amt} \| {bank}`；不 → INACTIVE |
| 1st of every month | 按月切段加总（见下） | **一律** `PRORATED(d/M - d/M \| N DAYS)@MONTHLY {amt} \| {bank}`（整段起止；N = 闭区间总天数；即使全是整月也不写 FULL MONTH；`{amt}` = 该账户原价，不是按比例算出的实际进账金额，见上方 1st of Every Month 的说明） |

##### 1st Resend 金额（按月切段加总，仍一笔 Due / 一笔 Post）

- 将用户 `[dayStart, dayEnd]` 按自然月切开；每段贡献 = 该段闭区间天数 / 该月总天数（整月 = 1）。
- 各月贡献加总为比例，再 × Buy / Sell / Profit / PS。
- 例：`6/10 – 7/31` → `(21/30) + 1`；例：`6/1 – 8/31` → `1 + 1 + 1`。
- Due / ledger / transactions 仍只有一笔 `RESEND_CONSOLIDATED`。

#### 1st of Every Month（已实现）

- 必须填 `dayStart`；`dayEnd` **改为选填**：
  - **有填**：需 `dayEnd >= dayStart`，补单窗口为整段 `[dayStart, dayEnd]`（可跨月），**不按自然月拆多笔**，走下方「按月切段加总」金额算法。
  - **不填（单月模式）**：窗口自动收窄为 `[dayStart, dayStart 所在月月末]`，仍沿用同一套「按月切段加总」算法——`dayStart` 为当月 1 号时自然收敛成整月全额（比例 = 1，等同 `FULL_MONTH`）；`dayStart` 为月中某天则自然变成 prorated（比例 = 当月剩余天数 / 该月总天数），不需要另外的整月判断分支。

#### Monthly（已实现）

- **只填** `dayStart`；`dayEnd` 禁用、不提交。
- 补单窗口自动为 `[dayStart, dayStart + 1 month]`（与正常 Monthly 一期一致）。
- 例：`dayStart = 6/20` → 补单 `6/20 – 7/20`，一笔 `RESEND_CONSOLIDATED`。
- 同样不按创建日 / 未到期过滤，Resend 成功即进 Accounting Due。

#### Once（已实现）

- Resend 产品规则与 Monthly 一致（只填 `dayStart`、冲突/覆盖/一律展示、`RESEND_CONSOLIDATED`）。
- 窗口用 **Once 自己的单日逻辑**（不是 Monthly 的 +1 month）：`postedDate = billingStart = billingEnd = dayStart`。
- 例：`dayStart = 6/20` → 补单单日 `6/20`。
- 不套用正常 Once 的「未到 dayStart 不出」「早于创建月跳过」；Resend 成功即进 Due。

#### Week（已实现）

- Resend 产品规则与 Monthly / Once 一致（只填 `dayStart`、冲突/覆盖/一律展示、只补一次）。
- 窗口用 **Week 自己的一周逻辑**：`[dayStart, dayStart + 6]`（含首尾 7 天），`postedDate = dayStart`。
- 例：`dayStart = 6/25` → 补单 `6/25 – 7/1`，一笔 `RESEND_CONSOLIDATED`。
- Accounting Due **Billing Date** 与正常 Week 一致，展示 `start – end`（from – to）。
- **不是**按正常 Week 从锚点滚到 today 出多周；只出用户本次选的这一周。

#### Day（已实现）

- Resend 产品规则与 Monthly / Once 一致（只填 `dayStart`、冲突/覆盖/一律展示、只补一次）。
- 窗口用 **Day 自己的单日逻辑**：`postedDate = billingStart = billingEnd = dayStart`。
- 例：`dayStart = 6/25` → 补单单日 `6/25`。
- **不是**按正常 Day 从锚点滚到 today 出多日；只出用户本次选的那一天。

---

## 32. Data Capture — Spring API 对齐说明

> 原始独立文件：`docs/datacapture-spring-api.md`（内容已合并于此；原文件已改为跳转说明）


> **前端仓库**：`../Count-frontend/`  
> **后端契约**：`DataCaptureGameDTO` + tenant 模型（无 JSON / 无 `scope_*`）  
> **最后更新**：2026-08-19（修复 2026-08-14 commit `4f00f14` 整批覆盖回退，见 §0；Formula CRUD / Account-Currency 下拉 / Summary Submit 重新切回 Spring，见 §2.4 / §2.5 / §2.6 / §2.7 / §2.8；Category 切换 toolbar 二次移除，见 §2.10；Summary populate / Add Account / 公司访问权限剩余 PHP 清理，见 §0.1；Edit Formula Save 漏带 processId 的 bug fix，见 §0.2）  
> **金额精度**：Summary processed amount 见 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」节（后端 `SummaryAmountFormat` + 前端 `summaryRowAmount.js`，ROUND_DOWN 6/8）

---

### 0. 2026-08-19：修复 commit `4f00f14` 造成的整批回退

`4f00f14`（2026-08-14，"new version frontend...page to springboot api"）用一份较旧的前端快照整批覆盖了 `pages/datacapture` / `pages/datacapturesummary` 下 66 个文件，把 2026-08-10 那批已完成的 Spring 迁移（Games 表单 / Formula CRUD / Account-Currency 下拉 / Summary Submit / Category toolbar 移除）静默改回了 PHP 版本，且没有更新本文档。`docs/frontend-springboot-migration.md` 第 2 节当时也仍标注"已 Spring"，与实际代码不符。

修复范围（HEAD 已核实，本文档以下各节按**当前真实代码状态**重写）：

| 能力 | 回退后状态 | 本次修复 |
|------|-----------|----------|
| Games 表单（process 列表/详情/币别） | `fetchProcessesByDay` + `fetchProcessDetail` + `fetchAddProcessFormData`（PHP） | 改回 `postGameCaptureForm` + `fetchCaptureCurrenciesByTenantId`（Spring，见 §2.1） |
| Description 目录 CRUD | `catalog_api.php` | 改回 `fetchCaptureDescriptionCatalog` / `postCaptureDescription` / `postCaptureDescriptionDelete`（Spring） |
| Category 切换 toolbar | 被恢复（Games\|Bank 按钮重新出现） | 二次移除，见 §2.10 / `CATEGORY_REMOVED.md` |
| 当日已提交 process 列表 | `submissions_api.php` | 改回 `postSubmittedProcesses`（Spring，见 §2.9） |
| Add/Edit/Delete Formula | `summary_templates_api.php`（snake_case） | 重写为 `saveAddFormulaSpring` / `saveUpdateFormulaSpring` / `deleteFormulasSpring`（Spring，见 §2.4 / §2.6 / §2.7） |
| Formula Account / Currency 下拉 | `summary_catalog_api.php` / `account_currency_api.php` | 改回 `/api/account/list` + `/api/currency/available`，PHP 仅空结果回退（见 §2.5） |
| Summary 最终 Submit | 全量走批次 PHP（`submitSummaryPayload`，不分 group/company） | 按 `isGroupLedgerCapture` 分流：Games/Bank（含 C168）→ 单次 `submitSummaryToSpring`；真 AP/IG group ledger → 保留批次 PHP（见 §2.8） |
| Bank/C168 payroll 表格草稿（company bucket） | `group_capture_draft_api.php?action=get\|save_group_capture_draft`（500，未修好的旧端点） | `dataCaptureGroupOnlyTableDraft.js` 按 bucket 分流：`company:{tenantId}` → Spring `saveBankCaptureDraft`/`getBankCaptureDraft`（`bank/draft/save\|get`，见 §2.3）；真 AP/IG group bucket 仍走 PHP `group_capture_draft_api.php`（未migrate，非本次范围） |
| Data Capture 页 Submit → Summary（BK/Bank 公司范围） | Submit 前先调 `submissions_api.php?action=get_group_process_id` 解析 process 数字 id，端点 500 导致整个 Submit 卡住报错、无法跳转 Summary | `useDataCaptureSubmitReset.js`：`get_group_process_id` 解析只在**真 group scope**（`captureScope.mode === "group"`）才调用；C168 / bank-only 公司范围（含普通 Bank 分类的公司 scope）直接带 `processCode` 字符串跳转 Summary，numeric processId 交给 Spring Submit（§2.8）自行按 `processCode` 解析/缺省自动建 |
| Bank draft 回填后表格被压缩（只剩 2 列） | `getBankCaptureDraft` 返回的 `tableData.colCount` 只反映 Spring `data_capture_draft_cell` 实际存的已填格边界（如 2 数据列），`snapshotToGrid()`（`grid/gridModel.js`）重建表格时**优先信任** `snapshot.colCount` 而不是调用方传入的 `requiredCols`，于是表格被缩到 2 列而不是固定的 11 列 | `dataCaptureGroupOnlyTableDraft.js` 新增 `normalizeBankDraftTableData()`：回填前把 `colCount`/`rowCount` 兜底提到 Bank/群组表格固定尺寸（`GROUP_ONLY_GRID_COLS+1` / `resolveDataCaptureGridDimensions(true).rows`），只在这条 Spring 回填路径生效，不动 `snapshotToGrid` 通用逻辑 |

`lib/dataCaptureSpringApi.js` 在回退期间**内容未被破坏**，只是没有任何地方 import 它（孤儿文件）；本次修复即把它重新接回 `useDataCaptureFormEngine.js` / `DescriptionSelectionModal.jsx` / `useDataCaptureCategoryPermissions.js` / `useDataCaptureSubmittedList.js` / `dataCaptureGroupOnlyTableDraft.js`。

---

### 0.1 2026-08-19（续）：Summary populate / Add Account / 公司访问权限 —— 清掉剩余 PHP 残留

**纯前端改动，本节仅作后端侧备份记录；完整细节（含改前/改后代码片段）见前端仓库**
[`Count-frontend/docs/datacapture-full-springboot-cleanup.md`](../../Count-frontend/docs/datacapture-full-springboot-cleanup.md)。

背景：§0 的回退修复之后，Summary 页面一打开就报「An unexpected error occurred.」——不是 Submit 本身的
问题，是 populate 阶段的 `fetchSummaryAccountList()` / `fetchSummaryTemplates()` 还在打
`summary_catalog_api.php` / `summary_templates_api.php` 这两个纯 PHP 端点（当前后端没有对应实现，
`apiUrl.js` 也没有重写这两个路径）。顺着这个 bug 把 `pages/datacapture` + `pages/datacapturesummary`
两个目录整个扫了一遍，结果：

| 能力 | 旧 PHP | 新方案 |
|------|--------|--------|
| Summary populate — 帐户下拉 | `summary_catalog_api.php` | `POST /api/account/list?tenant_id=`（复用 Account 页） |
| Summary populate — 回填已存公式 | `summary_templates_api.php?action=templates` | **复用 Maintenance > Formula 页的既有端点** `POST /api/maintenance/formula-maintenance/list`（前端按 `productType` 分组成 MAIN/SUB） |
| Summary 草稿跨刷新恢复 | `summary_state_api.php?action=get_summary_state` | 无需 API——`data_capture_summary_state` 本来就没建表，前端已经用 localStorage 落实 |
| Summary「+ Add Account」建帐户（company scope） | `addaccountapi.php` + N 次 `account_company_api.php` + M 次 `account_currency_api.php` | `POST /api/account/add`（`UserListDTO` 一次带 `tenantIds[]`/`currencyIds[]`，一个事务） |
| 同上 — 角色下拉 | `editdata_api.php` | 无需 API——跟 Account List 页一样，纯前端静态 `ROLE_PRIORITY` fallback |
| 同上 — 可选币别/建删币别 | `account_currency_api.php` / `create_currency_api.php` / `delete_currency_api.php` | `POST /api/currency/available\|add\|delete` |
| Data Capture 切公司 session 同步 | `api/session/update_company_session_api.php` | `syncCompanySessionApi()` → `POST auth/switch-tenant` |
| Data Capture 访问权限 fallback | `domain_api.php`（`get_company_permissions`） | `fetchTenantCategoryPermissions()`（读 `switch-tenant` 的 `has_game`/`has_bank`） |
| 浏览器还原（`restore=1`）补币别 | `processlist_api.php?action=get_process` | `postGameCaptureForm({ tenantId, captureDate, processPk })`（主流程本来在用的 Games 表单端点） |

**后端没有新增/修改任何端点或代码**——`formula-maintenance/list` 是复用 `MaintenanceController` /
`MaintenanceServiceImpl` 已有的实现（原本给 Maintenance > Formula 页用，该页面前端目前实际仍是 PHP，
是另一个未迁移缺口，不在本次范围）；其余全是前端接线到 Account / Auth 模块已存在的 Spring 端点。

**仍保留 PHP（真 AP/IG group ledger scope，未来一起处理）**：`get_group_process_id`、
`get_scope_account_currencies_api.php`、`group_capture_draft_api.php`、`summary_submit_api.php`、
以及 Summary Add Account 在 `groupOnlyAccountMode` 下的账户/币别调用——这几处早在 §4 就标注为暂不
迁移，本次未动。

---

### 0.2 2026-08-19（续二）：Edit Formula Save 报「Process Id is required」但改动其实没落库

**纯前端 bug fix，本节仅作后端侧备份记录；完整细节见前端仓库**
[`Count-frontend/docs/datacapture-full-springboot-cleanup.md`](../../Count-frontend/docs/datacapture-full-springboot-cleanup.md) §5。

**现象**：Games category 下 Edit Formula 点 Save，弹出 `Error: Process Id is required`，但那一行在
表格里看着已经改成新值——刷新后打回原样（其实没写进 `data_capture_formula`）。

**根因（前端）**：`saveUpdateFormulaSpring()` 组请求体时，只有 `row.templateId == null`
（无 `id`，走 business key 定位）那个分支会带 `processId`/`processCode`；有 `id` 时完全不带。但
`DataCaptureSummaryServiceImpl.updateFormula()`（本文件 §2.6 的后端实现）**不管请求有没有带 `id`，
第一步永远先** `resolveProcess(tenantId, processId, processCode, ...)`——`id` 定位（`resolveExistingForUpdate`）要等 process 解析完才轮到，缺了 `processId`/`processCode` 会在最前面就抛
`"Process Id is required"`。「看起来改成功了」是因为前端 `handleSave()` 在调 API **之前**先把新值
乐观写进本地表格状态，API 失败只弹 toast、不回滚。

**修法**：前端把 `processId`/`processCode` 改成不管有没有 `id` 都带上——纯前端改动，**后端代码/契约
未变**（`resolveProcess` 一直要求二者至少有一个，这是既有、合理的行为；本次只是前端漏发）。

---

### 1. 原则

- **前端对齐 Spring**，不要求后端迁就 PHP 字段（camelCase、`tenantId`、JSON body）。
- **Group / Company pill 的 `id` = `tenant.id`（数字）**；code 仅用于展示与 parent group 筛选。
- 公司列表与切换统一走 **`GET /auth/tenant-accessible`** 与 **`POST /auth/switch-tenant`**。

#### 1.1 表分工（tenant 模型）

| 表 | 作用 | 状态 |
|----|------|------|
| `data_captures` | Submit 头（GAME/BANK） | 已有 |
| `data_capture_description` | GAME 选中 description 多选桥 | 已有 |
| `data_capture_formula` | Summary / Maintenance **持久公式配置**（不绑 capture） | 已有 + Formula CRUD Spring |
| `data_capture_line` | Summary **最终 Submit 行快照**（绑 `capture_id`；替代 legacy `data_capture_details`） | **DDL 已写入**；Submit API 仍 PHP / 待 Spring |
| `data_capture_draft*` | BANK 表格草稿 | 已有 |

**明确不建（旧 PHP workaround，Spring 不需要）：**

| 旧表 | 原因 |
|------|------|
| `data_capture_submit_queue` | PHP `post_max_size` / 分批 Submit 用；Spring 可一次事务提交 `data_captures` + `data_capture_line` |
| `data_capture_summary_state` | 存 Summary UI `state_json`；公式已有 `data_capture_formula`，未提交草稿用前端 session/localStorage（BANK 表格另有 `data_capture_draft*`） |

---

### 2. 已迁移 Spring 的 Data Capture 能力

| 能力 | Spring 端点 | 前端入口 |
|------|-------------|----------|
| Group/Company 列表 | `GET /auth/tenant-accessible?all=1` | `fetchOwnerCompaniesAll()` → `tenantAccessibleApi.js` |
| 切换活动 tenant | `POST /auth/switch-tenant?tenant_id=` | `syncCompanySessionApi()` / `syncDataCaptureCompanySession()` |
| Games 按日 process 列表 + 选中回填 | `POST /api/datacapture/games/form` | `postGameCaptureForm()` → `dataCaptureSpringApi.js` |
| 币别 catalog | `POST /api/currency/list?tenant_id=` | `fetchCaptureCurrenciesByTenantId()` |
| Description catalog / CRUD | `POST /api/process/list-description` 等 | `processListApi.js`（经 `dataCaptureSpringApi.js` 封装） |
| 分类 pill（Games/Bank/…） | `/auth/switch-tenant` 返回 `has_game` / `has_bank` | `fetchTenantCategoryPermissions()` |
| Add Formula 保存 | `POST /api/datacapture-summary/formula/save` | `saveAddFormulaSpring()` → `summarySaveTemplatePure.js` |
| Edit Formula / Source 更新 | `POST /api/datacapture-summary/formula/update` | `saveUpdateFormulaSpring()`（按行 `id`；SUB 只改自己） |
| Formula 删除 | `POST /api/datacapture-summary/formula/delete` | `deleteFormulasSpring()`；MAIN 清骨架 / SUB 整行移除；无 subOrder 重排 |
| Add/Edit Formula Account 下拉 | `POST /api/account/list?tenant_id=` | `fetchSummaryFormCatalog()` → `summaryApi.js` |
| 选中 Account 后 Currency | `POST /api/currency/available?tenant_id=&account_id=` | `loadCurrenciesForAccount()` → 仅 linked 写入下拉并默认选中 |
| 当日已提交 GAME process 列表 | `POST /api/datacapture/games/submitted` | `fetchSubmissionsByCaptureDate()` → `postSubmittedProcesses()`（`dataCaptureSpringApi.js`），右侧 submitted 面板 |

#### 2.2 Games Submit → Summary（本阶段：localStorage，不写库）

点 **Submit**：校验 → `saveCaptureSession` → `navigate(/datacapturesummary)`。  
**不调用** `summary_submit_api`；DB 落库在 Summary 最终 Submit 另做。

| 步骤 | 前端文件 |
|------|----------|
| Submit 编排 | `hooks/useDataCaptureSubmitReset.js` |
| 写/读 session | `lib/dataCaptureStorage.js` |
| Summary 加载 | `datacapturesummary/lib/summaryStorage.js` |
| 表头 | `components/SummaryProcessInfo.jsx` |
| 列表行（Id Product） | `table/summaryColumnAData.js` → `buildInitialSummaryRows` |

**`capturedProcessData` 主要字段：** `date`, `tenantId`, `process`（pk）, `processCode`, `processName`, `descriptions[]`, `currency`, `currencyName`, `remark`, `removeWord`, `replaceWordFrom`, `replaceWordTo`, `scopeCompanyId`, `dataCaptureType`。

**`capturedTableData`：** grid 快照；列 A（index 1）→ Summary Id Product 行。

**Scoped key：** `capturedTableData:{tenantId}` + `dc_capture_active_scope_key`。

**列表优先：** 模板 API（仍 PHP）失败时仍展示 Id Product 骨架行。

#### 2.3 Bank Submit → Summary + Draft（Phase 1 + 2）

Bank 形态（C168 / bank-only company / group payroll UI，或 `selectedPermission === "Bank"`）：

| 规则 | 说明 |
|------|------|
| Submit 校验 | **Process + Currency + Capture Table**（无 Description） |
| Session | 同 §2.2；额外 `category: "BANK"`、`processCode`（PROFIT/SALARY/COMMISSION/BONUS） |
| process pk | 尽量解析 `process.id`；**失败不挡 Submit** — Summary 用 `processCode` 展示表头，用表格列 A 出列表 |
| **Draft 写** | **仅 Submit 时**；`POST /api/datacapture/bank/draft/save` → `data_capture_draft` + `_cell` |
| **Draft 读** | 选 process+currency 时 `POST /api/datacapture/bank/draft/get` → 回填表格 |
| Draft 粒度 | `UNIQUE (tenant_id, process_id, currency_id)` — 同 process 不同 currency 分存 |
| Draft process | **SALARY / COMMISSION / BONUS** 写入；**PROFIT 永不写入/不回填** |
| Remark | 进 session 表头；**不进 draft** |
| 缺省 BANK process | save 时若无 `process.category=BANK` 行，按 code + currency **自动创建** |

**Draft API body：**

```json
{
  "tenantId": 52,
  "processCode": "SALARY",
  "currencyId": 13,
  "tableData": { "rows": [/* capture snapshot */] }
}
```

或显式 `cells: [{ "rowIndex": 0, "colIndex": 1, "cellValue": "AAA" }]`（`rowIndex` 0-based，`colIndex` 1-based）。

自测：BK + SALARY + MYR + 表格 → Submit → Summary；再回 DC 选 SALARY+MYR → 表格带回 AAA/111；PROFIT Submit 不写 draft。

#### 2.4 Add Formula 保存（`data_capture_formula`）

**`POST /api/datacapture-summary/formula/save`**

请求（`DataCaptureSummaryDTO`，camelCase）：

```json
{
  "tenantId": 42,
  "processId": 15,
  "processCode": "SALARY",
  "idProduct": "AAA",
  "accountId": 8,
  "currencyId": 3,
  "description": "Win",
  "sourceColumns": "$2,$3",
  "formula": "$2+$3",
  "formulaOperators": "$2+$3",
  "sourcePercent": "1",
  "enableSourcePercent": true,
  "enableInputMethod": false,
  "rowIndex": 3
}
```

- `processId` — process 主键（有则优先）  
- `processCode` — Bank Summary 常只有 code（如 `SALARY`）；无 `processId` 时服务端按 code 解析，缺省 BANK process 则自动创建（同 draft save）

**落库规则（Add）：**

| 条件 | 行为 |
|------|------|
| 该 `tenantId + processId + idProduct` 尚无 MAIN，或 MAIN 的 `account_id` 为空 | 写 **MAIN**（有空 MAIN 则 UPDATE 填入；否则 INSERT） |
| 已有带 `account_id` 的 MAIN | 插入 **SUB**（`parent_id_product = idProduct`，`sub_order = max+1`） |

响应 `data` 带回 `id`、`productType`（`MAIN`/`SUB`）、`subOrder`、`formulaVariant` 等。

前端：Summary **Add Formula**（`mode === "new"`）走 `saveAddFormulaSpring`（传 `processId` + `processCode`）；成功后关闭弹窗。

自测：
1. Bank SALARY（无数字 processId）→ Add Formula Save → body 带 `processCode:"SALARY"`，不再报 `processId is required`；成功后弹窗关闭。
2. 空 main 行 → DB `product_type=MAIN`；同 product 再 Add → `SUB`。

#### 2.6 Edit Formula / Source 行内更新（按行 `id`）

**`POST /api/datacapture-summary/formula/update`**

```json
{
  "id": 12,
  "tenantId": 42,
  "accountId": 8,
  "currencyId": 3,
  "formula": "$2+$3",
  "formulaOperators": "$2+$3",
  "sourcePercent": "0.5",
  "enableSourcePercent": true,
  "description": "Win"
}
```

| 规则 | 说明 |
|------|------|
| 定位 | 优先 `id`（`templateId`）；**Bank 无 id 时**用 `processCode` + `productType` + `idProduct` + `accountId`（SUB 再加 `parentIdProduct` + `subOrder`） |
| 身份字段 | **不改** `product_type` / `parent_id_product` / `sub_order` / `id_product` |
| MAIN / SUB | SUB 编辑只更新自己，不影响 MAIN 或其他 SUB |
| Edit Formula | 铅笔打开 → 回填当前 row → Save → `saveUpdateFormulaSpring` → 成功关弹窗 |
| Source 行内 | 双击 Source → **Enter 或离开单元格（blur）** → `saveUpdateFormulaSpring`；未变不请求 |

自测：
1. Bank SALARY（无数字 processId / 无 templateId）→ Edit Formula Save → body 带 `processCode:"SALARY"` + `productType` + `accountId`，不再报 `Formula id is required`；成功关弹窗。
2. MAIN 铅笔 Edit → 只更新该 MAIN；SUB 铅笔 Edit → 只更新该 SUB。
3. 双击 Source 改为 `0.5` → Enter/blur → `POST .../formula/update`，DB `source_percent=0.5`。

#### 2.7 Formula 删除（硬删；无 subOrder 重排）

**`POST /api/datacapture-summary/formula/delete`**

```json
{
  "tenantId": 42,
  "processId": 15,
  "processCode": "SALARY",
  "items": [
    { "id": 12 },
    {
      "productType": "SUB",
      "idProduct": "AAA",
      "parentIdProduct": "AAA",
      "accountId": 9,
      "subOrder": 1
    }
  ]
}
```

| 规则 | 说明 |
|------|------|
| 定位 | 每项优先 `id`（`templateId`）；无 id 时用 business key（同 update） |
| 删除 | 硬 `DELETE` `data_capture_formula`；已不存在则跳过（幂等） |
| MAIN / SUB | 都只删对应 DB 行；**不做** subOrder 重排 |
| UI | 先调 Spring，再本地：MAIN（及无父级的孤儿行）清数据留骨架；真 SUB 整行移除 |
| Process | 按 `processId` / `processCode` **查找**，不自动创建 Bank process |
| 前端 | `deleteFormulasSpring`（`summarySaveTemplatePure.js`）← `useSummaryPageActionsPure.handleDeleteSelected`；**不再**走 PHP `delete_template` / `syncSubOrderTemplates` |

响应：

```json
{
  "success": true,
  "message": "Formula Deleted Successfully",
  "data": { "deletedCount": 2, "deletedIds": [12, 15] }
}
```

请求/响应均使用 **`DataCaptureSummaryDTO`**（`items` 为待删列表；`deletedCount` / `deletedIds` 为结果字段）。校验文案与 Spring 对齐：`Tenant Id is required` / `Process Id is required` / `items are required` 等。

自测：
1. 勾选 MAIN → Delete → Network `POST .../formula/delete` → DB 行消失，UI **立刻**留空骨架（Id Product 仍在，无需手动 Refresh）。
2. 勾选 SUB → Delete → 仅该 SUB 从 DB/UI 移除；其余 SUB 的 `sub_order` **不**重排。
3. Bank（仅 `processCode`）→ body 带 `processCode`，无 `Formula id is required`。

#### 2.5 Add/Edit Formula — Account / Currency catalog

弹窗打开时 `useSummaryEditFormulaPure` 调 `fetchSummaryFormCatalog(captureScope, companyId)`：

| 数据 | 优先 | 回退 |
|------|------|------|
| **Accounts** | `POST /api/account/list?tenant_id=` → `filterAccountListRows`（仅 active） | PHP `summary_catalog_api.php`（Spring 为空时） |
| **Currencies**（未选 account 时的初始下拉） | `POST /api/currency/list?tenant_id=` → `fetchCaptureCurrenciesByTenantId` | 同上 PHP catalog |

`tenantId` = `resolveDataCaptureEffectiveTenantId(captureScope, companyId)`（pill / scope 的数字 tenant id）。

**选中 Account 后（立即）：**

| 步骤 | 行为 |
|------|------|
| 请求 | `POST /api/currency/available?tenant_id=&account_id=`（`fetchAvailableCurrencies`） |
| 下拉选项 | 仅 `is_linked === true` 的币别（该 account 已关联的，如 Edit Account 里的 MYR） |
| 默认选中 | 单币别直接选中；多币别优先 MYR，否则第一项 |

展示文案：`formatSummaryAccountDisplay` 读 Spring 归一化字段 `id` / `account_id` / `name` / `role`。

自测：
1. Summary → Add Formula → 点 Account → Network 可见 `POST /api/account/list?tenant_id=`，下拉出现 active 账户。
2. 选中已关联 MYR 的账户（如 BK SHA 2）→ 立即 `POST /api/currency/available?...&account_id=` → Currency 下拉出现 MYR 并自动选中。

#### 2.1 Games 表单 API

**`POST /api/datacapture/games/form`**

请求（`DataCaptureGameDTO`）：

```json
{
  "tenantId": 42,
  "captureDate": "2026-07-27",
  "id": 15
}
```

- `tenantId` — 必填，subsidiary 或 group entity 的 tenant pk  
- `captureDate` — 必填，`YYYY-MM-DD`  
- `id` — 可选，process 表主键；有则返回 `selectedProcess` 详情  

响应：

```json
{
  "success": true,
  "message": "success",
  "data": {
    "tenantId": 42,
    "captureDate": "2026-07-27",
    "processes": [
      {
        "id": 15,
        "processId": "WM",
        "descriptionName": "FOOTBALL",
        "processDisplay": "WM (FOOTBALL)"
      }
    ],
    "selectedProcess": {
      "id": 15,
      "processId": "WM",
      "currencyId": 3,
      "currencyCode": "MYR",
      "removeWord": "TEST",
      "replaceWordFrom": "A",
      "replaceWordTo": "B",
      "remark": "process config remark",
      "descriptionNames": ["FOOTBALL", "BASKETBALL"]
    }
  }
}
```

前端映射：

| Spring 字段 | UI 用途 |
|-------------|---------|
| `id` | process 主键（select value） |
| `processId` | 业务码（原 PHP `process_id`） |
| `processDisplay` | 下拉展示文案 |
| `descriptionName` | 列表行上的单一描述摘要 |
| `descriptionNames` | 多选 description 回填 |
| `currencyId` / `currencyCode` | 币别下拉 |
| `removeWord` / `replaceWordFrom` / `replaceWordTo` | 词过滤字段 |
| `remark` | process 配置备注（选中 process 时回填） |

校验错误（HTTP 200 + `success: false`）示例：

- `Not logged in`
- `tenantId is required`
- `captureDate is required`
- `Process not found`

#### 2.8 Summary 最终 Submit（`data_captures` + `data_capture_line` + `transactions`）

**`POST /api/datacapture-summary/submit`**

一次请求 = 一个事务：写 `data_captures`（头）+ `data_capture_line`（逐行）+ `transactions`（非零行 WIN/LOSE）+ GAME 才写 `process_submitted`。**不分批**（Spring 无 PHP `post_max_size` 限制，见 `docs/TABLE_MIGRATION.md` §4）。

请求（`DataCaptureSummarySubmitDTO`）：

```json
{
  "tenantId": 52,
  "category": "BANK",
  "processId": null,
  "processCode": "SALARY",
  "captureDate": "2026-08-07",
  "currencyId": 13,
  "remark": "",
  "removeWord": "",
  "replaceWordFrom": "",
  "replaceWordTo": "",
  "lines": [
    {
      "productType": "MAIN",
      "idProduct": "AAA",
      "accountId": 8,
      "currencyId": 13,
      "sourcePercent": "1",
      "enableSourcePercent": true,
      "formula": "1111 * 2222",
      "processedAmount": "2469531.000000",
      "rateValue": null
    }
  ]
}
```

响应：`{ "success": true, "message": "...", "data": { "captureId": 901 } }`

| 规则 | 说明 |
|------|------|
| 金额重算 | 后端**不信**客户端 `processedAmount` 原值——用 `SummaryAmountFormat.finalizeProcessedAmount` 强制截断（ROUND_DOWN 6 位）后才落库；见 §3.1 |
| Submit 门槛 | 所有行重算后金额高精度求和，`SummaryAmountFormat.isTotalWithinSubmitTolerance`（±0.05）不过则整单拒绝，不写任何表 |
| GAME 拦截 | `category` 解析为 GAME 时，若当日该 process 已提交（`process_submitted`）直接拒绝；**BANK 不受此限**，可重复提交 |
| Process 解析 | 有 `processId` 优先；否则按 `processCode` 解析（Bank 缺省自动建，同 §2.4） |
| `transactions` 记录 | 每个非零行一笔：`amount` 存绝对值，`transactionType` 由正负号定 `WIN`/`LOSE`；`description` 格式 `"{processCode}: {formula}"`（见 `docs/transaction-description-rules.md`「WIN / LOSE」节） |
| Customer/Domain Report | 这次只保证落库；报表页面本身未实现。**Bank 提交不产出 report 数据**——报表查询将来加 `data_captures.category = 'GAME'` 过滤，不需要额外字段 |
| Transaction Payment / Payment History 可见性 | 这里写的 `WIN`/`LOSE` 行 `bank_process_posted_id` 恒为 `NULL`（不经过 Bank Process 记账流程）。Transaction 模块原本的 WIN/LOSE 查询都要求 `bank_process_posted_id IS NOT NULL`，一度导致这些行在 Transaction Payment / Payment History 里完全查不到；已补齐对称聚合并让 `ID PRODUCT` 显示 `DATA CAPTURE`，详见 [transaction-datacapture-winloss.md](./transaction-datacapture-winloss.md) |

**前端调用（Count-frontend）：**

| 文件 | 职责 |
|------|------|
| `datacapturesummary/lib/summaryApi.js` | `submitSummaryToSpring(payload)` — 单次 POST，不分批 |
| `datacapturesummary/submit/summarySubmitExecution.js` | `executeSummarySubmit(...)`：**Games/Bank 公司范围**（含 C168/bank-only 的 group payroll UI）→ 新 Spring 一次性提交；**真正的 AP/IG group ledger**（`isGroupLedgerCapture` 为 true）→ 仍走旧 PHP `submitSummaryPayload` + 分批（因为该场景的 tenant/process 解析仍依赖 PHP-only 的 `get_group_process_id`，见 §4） |
| `datacapturesummary/submit/buildSubmitRowsFromModel.js` | **未改**——仍产出旧字段命名的行对象；`summarySubmitExecution.js` 内的 `toSpringLine()` 负责改名/裁剪成 `DataCaptureLineDTO` 形状，不再往下游传 `account`/`currency`（展示用字符串）、`templateId`/`templateKey`/`subOrder`/`rateChecked`/`batchSelection`/`inputMethod` 等 Spring 不需要的字段 |

**已知限制（有意保留，非本次范围）：** 真 AP/IG group ledger 提交仍是 PHP 路径（`get_group_process_id` 未迁移）；该分支的批次二分重试（size-error split）也一并简化掉了，只保留固定大小分批——量级极少触发，之后要迁移就直接连 Spring 一起换掉。

自测：
1. BK company（如 C168）→ Bank SALARY + MYR + 表格 → Submit → Network 只有一次 `POST /api/datacapture-summary/submit`（无分批）；响应带 `captureId`。
2. 同一 BK SALARY 当天再次 Submit → 正常成功（不拦截）。
3. Games process 当天 Submit 一次后，再选同一 process → Data Capture 页 process 下拉不再出现它（`process_submitted` 生效）；若绕过 UI 直接再 Submit → 后端拒绝 `This process has already been submitted today`。
4. 合计故意超 ±0.05 → Submit 被拒绝，`transactions`/`data_capture_line`/`data_captures` 均无新增行。

#### 2.9 当日已提交 Process 列表（右侧 submitted 面板）

**`POST /api/datacapture/games/submitted`**

GAME Submit（§2.8）成功当天会写一行 `process_submitted`；右侧「Submitted Processes」面板就是把这张表 join `process` + `process_description` 后原样展示：process code + description、`created_by`、`created_at`。**BANK 不写 `process_submitted`，不会出现在这个列表。**

请求（`DataCaptureGameDTO`）：

```json
{
  "tenantId": 42,
  "captureDate": "2026-08-10"
}
```

- `tenantId` — 必填，同 §2.1（`resolveDataCaptureTenantId(scope)`，即 `scope.scopeCompanyId`）
- `captureDate` — 必填，`YYYY-MM-DD`

响应：

```json
{
  "success": true,
  "message": "success",
  "data": [
    {
      "id": 901,
      "processId": "MEGA16397S0",
      "descriptionName": "MEGA888 API",
      "processDisplay": "MEGA16397S0 (MEGA888 API)",
      "createdBy": "admin01",
      "createAt": "2026-08-10T10:11:32",
      "captureDate": "2026-08-10"
    }
  ]
}
```

前端映射（`DataCapturePage.jsx` `.submitted-column`）：

| Spring 字段 | UI 用途 |
|-------------|---------|
| `id` | `process_submitted.id`，React key |
| `processId` | 业务码（原 PHP `process_code`）；group scope / company payroll channel 时**单独**展示（不带 description） |
| `processDisplay` | 非 group scope 时的展示文案，等价于旧 `"{process_code} ({description_name})"`；前端经 `displayTextFromProcessRow()` 读取 |
| `createdBy` | 提交人 login_id（原 PHP `submitted_by`） |
| `createAt` | 提交时间，`formatSubmittedProcessDateTime()` 格式化为 `DD/MM/YYYY HH:MM:SS` |
| `captureDate` | `createAt` 缺失时的兜底（理论上不会发生，`process_submitted.created_at` 非空） |

**已知限制（与 §2.1 Games 表单一致，非本次新增）：** `tenantId` 只接受单一数字，真 group aggregate 模式（多公司合并）下 `resolveDataCaptureTenantId(scope)` 会解析成 `null`，面板直接给出「tenantId is required」错误态（带 Retry 按钮），不再像旧 PHP 端点那样跨公司聚合展示。因为同一 group aggregate 模式下 Games 表单本身也无法通过 Spring 加载，这不是这次改动引入的新缺口。

自测：
1. Games process 当天 Submit 一次（见 §2.8 自测 3）→ 右侧面板出现该 process，格式 `CODE (description)`，`created_by`/时间与提交账号、当前时间一致。
2. 同一 process 当天再选一次 → 下拉里已不出现（`process_submitted` 生效）；不影响面板已展示的记录。
3. 切换 capture date → Network 出现新的 `POST /api/datacapture/games/submitted`，面板刷新为该日期的记录（无记录时显示 `noProcessesSubmitted`）。
4. Bank Submit（§2.8 自测 1）→ 面板**不**出现该笔（`process_submitted` 只记 GAME）。

---

### 3. 前端改动文件（2026-07-27；金额算法 2026-07-29；Summary Submit 切 Spring 2026-08-07；已提交列表 2026-08-10；`4f00f14` 回退修复 2026-08-19）

| 文件 | 说明 |
|------|------|
| `pages/datacapture/lib/dataCaptureSpringApi.js` | Spring 直调封装（Games 表单/已提交列表/币别/description CRUD/Bank draft/category flags）。2026-08-14 曾被 `4f00f14` 整批覆盖后失去所有 import（孤儿文件，内容本身未被破坏）；2026-08-19 重新接回 |
| `pages/datacapture/lib/dataCaptureTenant.js` | scope → `tenantId` 解析（`resolveDataCaptureTenantId` / `resolveDataCaptureEffectiveTenantId`） |
| `pages/datacapture/hooks/useDataCaptureFormEngine.js`（2026-08-19 重新切 Spring） | Games/company 模式下 process 列表 + 选中详情统一走 `postGameCaptureForm`（不带 `processPk` 取列表，带 `processPk` 取详情，映射回原有 snake_case 内部字段名）；币别走 `fetchCaptureCurrenciesByTenantId`；Bank 模式（硬编码 PROFIT/SALARY/COMMISSION/BONUS）与 group-only 币别聚合不变（仍 PHP，见 §4） |
| `pages/datacapture/components/DescriptionSelectionModal.jsx`（2026-08-19） | 改用 `fetchCaptureDescriptionCatalog` / `postCaptureDescription` / `postCaptureDescriptionDelete`；prop 由 `companyId` 改 `tenantId` |
| `pages/datacapture/hooks/useDataCaptureCategoryPermissions.js`（2026-08-19 重写） | key 改为 `tenantId`；改用 `fetchTenantCategoryPermissions`；**不再暴露 `selectPermission`/`showPermissionFilter`**——无 UI 可切，自动挑选 Games 优先、否则 Bank（见 §2.10） |
| `pages/datacapture/DataCapturePage.jsx`（2026-08-19） | 新增 `categoryTenantId`（`resolveDataCaptureEffectiveTenantId(captureScope, companyId)`）传给上面两个模块；**移除** Category 切换 toolbar（`#data-capture-permission-filter`，见 §2.10 / `CATEGORY_REMOVED.md`）；submitted 面板改读 Spring camelCase 字段（`processId`/`processDisplay`/`createdBy`/`createAt`，复用既有 `displayTextFromProcessRow()` 判断口径） |
| `pages/datacapture/hooks/useDataCaptureSubmittedList.js`（2026-08-19） | 改用 `postSubmittedProcesses({tenantId, captureDate})`（`tenantId = resolveDataCaptureTenantId(captureScope)`）；不再接受 `permissionCategory` 参数——Spring 端点本身不按分类过滤，Bank 永不写 `process_submitted` 故不会出现 |
| `pages/datacapture/lib/dataCaptureApi.js`（2026-08-19） | `formatSubmittedProcessDateTime()` 改读 `createAt`/`captureDate`（原 `created_at`/`capture_date`）；`fetchProcessesByDay`/`fetchProcessDetail`/`fetchAddProcessFormData`/`fetchDescriptionCatalog`/`postAddDescription`/`postDeleteDescription`/`fetchCompanyPermissionsForDataCapture`/`fetchSubmissionsByCaptureDate` 等 PHP 函数仍保留导出（`useDataCaptureSubmitReset.js` 的 restore 回填、`dataCaptureCompanyAccess.js` 的页面权限门槛仍在用），只是不再被 Games 表单主流程调用 |
| `pages/datacapture/lib/dataCaptureGroupOnlyTableDraft.js`（2026-08-19） | 新增 `resolveDraftBackend(bucketId)`：`company:{tenantId}` bucket → Spring `saveBankCaptureDraft`/`getBankCaptureDraft`；真 group bucket（AP/IG）→ 原 `dataCaptureGroupDraftApi.js`（PHP，未migrate）。`fetchGroupOnlyTableDraft`/`flushGroupOnlyTableDraftToServer`/`clearGroupOnlyTableDraft`/`scheduleServerDraftSave` 均改走这个分流；`bankDraftBackend.fetch` 另外用 `normalizeBankDraftTableData()` 把回填的 `colCount`/`rowCount` 兜底提到固定 11 列表格尺寸，避免 Spring 端只回填已填格边界导致表格被压缩 |
| `pages/datacapture/hooks/useDataCaptureSubmitReset.js`（2026-08-19） | Submit 里的 `get_group_process_id` 解析加上 `captureScope?.mode === "group"` 前置条件，避免 C168/Bank 公司范围也误打这支未 migrate 的 PHP 端点（原本会 500 卡住 Submit，无法跳转 Summary） |
| `pages/datacapturesummary/table/summaryRowAmount.js` | Summary 金额算法（与后端 `SummaryAmountFormat` 对齐：rate 8 位截断 → 最终 6 位截断；展示 HALF_UP 2）；`truncateProcessedAmountTo6Decimals` 现由 `summarySubmitExecution.js` 的 `toSpringLine()` 复用 |
| `pages/datacapturesummary/submit/buildSubmitRowsFromModel.js` | 未改——仍产出旧字段命名的行对象（`account`/`currency`/`templateKey`/`subOrder` 等展示/legacy 字段都在，供 group-ledger PHP 分支用）；`summarySubmitExecution.js` 内的 `toSpringLine()` 负责挑出 Spring 需要的字段并把 `processedAmount` 转 6 位字符串 |
| `pages/datacapturesummary/submit/summarySubmitTotalPure.js` | 合计 ±0.05 门槛（客户端预检，不变） |
| `pages/datacapturesummary/submit/summarySubmitRowGuard.js` | 行守卫用 6 位 amount（不变） |
| `pages/datacapturesummary/formula/summarySaveTemplatePure.js`（2026-08-19 重写） | 整个重写：`saveAddFormulaSpring` / `saveUpdateFormulaSpring` / `deleteFormulasSpring` 对接 `POST /api/datacapture-summary/formula/save\|update\|delete`；`buildTemplateKey()` 保留（`SummaryContext.jsx` 用它算本地去重 key，跟后端 `id` 无关）；旧的 PHP `buildTemplatePayloadFromRow`（snake_case payload + `last_processed_amount`）已删除 |
| `pages/datacapturesummary/context/SummaryContext.jsx`（2026-08-19） | `deleteSelectedRows()` 的 `templatesToDelete` 补上 `idProduct`/`parentIdProduct`/`accountId`/`subOrder`，供 `deleteFormulasSpring` 在没有 `id`（Bank 场景）时按 business key 定位 |
| `pages/datacapturesummary/hooks/useSummaryEditFormulaPure.js`（2026-08-19 重写） | Add/Edit Formula 弹窗：`handleSave` 按 `mode` 分流 `saveAddFormulaSpring` / `saveUpdateFormulaSpring`；Account 下拉改 `fetchAccountListByTenantId`（`/api/account/list`，仅 active，PHP 目录仅空结果时回退）；选中 Account 后 Currency 改 `fetchAvailableCurrencies`（`/api/currency/available`，仅 `is_linked`）；移除 `syncSubOrderTemplates`（后端不做 sub_order 重排，见 §2.7） |
| `pages/datacapturesummary/hooks/useSummaryPageActionsPure.js`（2026-08-19 重写） | `handleDeleteSelected` 改一次性调用 `deleteFormulasSpring(templatesToDelete, ...)`；同样移除 `syncSubOrderTemplates` 循环 |
| `pages/datacapturesummary/DataCaptureSummaryPagePure.jsx`（2026-08-19） | Source 行内编辑（双击 → Enter/blur）的 `handleInlineEditSave` 改调 `saveUpdateFormulaSpring`（补上 `processCode` 入参） |
| `pages/datacapturesummary/table/summarySubOrderResequence.js`（2026-08-19） | 删除 `syncSubOrderTemplates`（已无调用方）；`resequenceSubOrdersInRows`/`resequenceAllSubOrders`（纯前端本地展示排序，不打 API）保留不变 |
| `pages/datacapturesummary/formula/editFormulaFormState.js` | 状态存真值/6 位；display 才 round 2（未改） |
| `pages/datacapturesummary/lib/summaryApi.js`（2026-08-19） | 新增 `submitSummaryToSpring()`（`POST /api/datacapture-summary/submit`，单次不分批）；原 `submitSummaryPayload()` 保留给 group-ledger 分支用 |
| `pages/datacapturesummary/submit/summarySubmitExecution.js`（2026-08-19 重写） | 按 `isGroupLedgerCapture` 分流——Games/Bank 公司范围（含 C168/bank-only payroll）走新 `executeSpringSubmit`（单次提交；`category` 按 `processCode` 是否属于 PROFIT/SALARY/COMMISSION/BONUS 判定 GAME/BANK）；真 AP/IG group ledger 走保留下来的旧 PHP 分批逻辑 `executeLegacyGroupLedgerSubmit`（原函数体未改，只是改了名字）；新增 `toSpringLine()` 做行字段改名/裁剪 + 6 位金额截断 |

Group/Company picker **未改 UI 行为**：仍用 `fetchOwnerCompaniesAll()`（底层已是 `tenant-accessible`）。

#### 3.1 Summary processed amount（前后端同管线）

完整规则与示例见 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」。

要点：

1. **截断 ROUND_DOWN（向零）**，不是 Transaction 那套 HALF_UP-到上限。
2. 走 rate → 中间 **8** 位；最终入库 / Submit payload → **6** 位（8→6 先截断再算最终值）。
3. 前端格子 HALF_UP **2** 仅展示；**禁止**用 display 值作 payload / 合计 / 模板 fallback。
4. 合计 HALF_UP 2 后须在 **±0.05** 才可 Submit。
5. 后端：`SummaryAmountFormat` + `TransactionMoneyFormat.truncate*`；前端：`summaryRowAmount.js` 的 `resolveSubmitProcessedAmount`。

---

### 4. 仍走 PHP 的部分（待后端）

| 能力 | 旧 PHP | 说明 |
|------|--------|------|
| Group payroll process id 解析 | `get_group_process_id` | **仅真 AP/IG group ledger**（`isGroupLedgerCapture` 为 true）；C168 / bank-only company payroll 已随 Submit 一起切 Spring（见 §2.8），不再依赖此接口。**仍是 `submissions_api.php` 上唯一没迁移的 action**（`fetchGroupProcessIdByCode()`）——当日已提交列表已切 Spring，见 §2.9 |
| Group 币别聚合 | `get_scope_account_currencies_api.php` | group ledger scope |
| Submit / Summary | ~~`api/datacapture_summary/summary_submit_api.php`~~（Games/Bank 公司范围） | **Spring** `POST /api/datacapture-summary/submit`（见 §2.8）：一次事务写 `data_captures`+`data_capture_line`+`transactions`（+GAME 写 `process_submitted`）。**仅真 AP/IG group ledger 仍走 PHP**（因 tenant/process 解析依赖上一行未迁移的 `get_group_process_id`），沿用旧分批提交 |
| Bank draft 表格 | ~~`group_capture_draft_api.php`~~ | **Spring** `POST /api/datacapture/bank/draft/save|get`（`data_capture_draft*`）；PROFIT 排除 |
| Add Formula 保存 | ~~`summary_templates_api.php?action=save_template`~~（Add 路径） | **Spring** `POST /api/datacapture-summary/formula/save`（`data_capture_formula`）；MAIN 无数据→写 MAIN，已有→插 SUB |
| Edit Formula / Source 更新 | ~~`summary_templates_api.php?action=save_template`~~（Edit / Source） | **Spring** `POST /api/datacapture-summary/formula/update`（按 `id`；SUB 只改自己；Source 为 Enter/blur） |
| Formula 删除 | ~~`summary_templates_api.php?action=delete_template`~~ | **Spring** `POST /api/datacapture-summary/formula/delete`（`deleteFormulasSpring`；MAIN 清骨架 / SUB 移除；无 subOrder 重排） |
| Formula Account 下拉 | ~~`summary_catalog_api.php`~~（优先路径） | **Spring** `POST /api/account/list`；PHP catalog 仅作空结果回退 |
| 选中 Account 后币别 | ~~`account_currency_api.php`~~ | **Spring** `POST /api/currency/available?tenant_id=&account_id=`（仅 linked） |

---

### 5. 本地验证清单

1. 登录后打开 `/datacapture`，Group/Company pill 数据来自 `tenant-accessible`。
2. 选 subsidiary company → Network 可见 `POST /api/datacapture/games/form`（仅 `tenantId` + `captureDate`）。
3. 选 process → 同上接口带 `id`；表单回填 `currencyId`、`descriptionNames`、词过滤字段。
4. 打开 Description 弹窗 → `POST /api/process/list-description`（body 为 tenant id 数字）。
5. 切换 company → `POST /auth/switch-tenant`；Games/Bank pill 随 `has_game` / `has_bank` 变化。
6. **Bank Phase 1：** bank-only company（如 BK）→ Process SALARY + Currency + 表格 → Submit → Summary 表头/Id Product 行；F12 localStorage 可见 `category:"BANK"`、`processCode`、`capturedTableData:{tenantId}`。
7. **Bank Phase 2：** Submit 后 Network 可见 `POST /api/datacapture/bank/draft/save`；再选 SALARY+同 currency → `POST .../bank/draft/get` 回填表格；PROFIT 不出现 save。
8. **Add Formula：** Summary 空 main 行点 Add Formula → Save → `POST /api/datacapture-summary/formula/save`，`data_capture_formula.product_type=MAIN`；同 product 再 Add → `SUB`。
9. **Formula Account 下拉：** 打开 Add/Edit Formula → Network 可见 `POST /api/account/list?tenant_id=`；Account 搜索框下方列出 active 账户。
10. **选中 Account → Currency：** 选 BK SHA 2（Edit Account 已挂 MYR）→ 立即 `POST /api/currency/available?...&account_id=`；Currency 出现 MYR 并自动选中。
11. **Edit Formula：** 铅笔打开 → 改字段 Save → `POST .../formula/update`；SUB 只更新自己；成功关弹窗。
12. **Source 行内：** 双击 Source → 改值 → Enter 或 blur → `POST .../formula/update` 写 `source_percent`。
13. **Formula 删除：** 勾选 → Delete → `POST .../formula/delete`；MAIN 清 UI 骨架 + DB 硬删；SUB 整行移除；无 subOrder 重排。
14. **Summary 最终 Submit：** Games/Bank 公司范围 → Summary 点 Submit → Network 仅一次 `POST /api/datacapture-summary/submit`（不再分批）；成功后 `transactions`/`data_captures`/`data_capture_line` 三张表都能查到新行；详细自测见 §2.8。
15. **当日已提交列表：** Games process Submit 后返回 Data Capture 页 → 右侧面板 Network 可见 `POST /api/datacapture/games/submitted`（body 仅 `tenantId` + `captureDate`）；列表展示 `CODE (description)` + 提交人 + 时间；详细自测见 §2.9。

---

### 6. 维护约定

- 新增 Data Capture Spring 接口时：**先更新本文 + `DataCaptureGameDTO`**，再改 `dataCaptureSpringApi.js`。
- 勿在 Spring 层恢复 snake_case 兼容；前端在 API 边界做 normalize（仅读取时兼容旧 session storage 可保留双读）。
- 与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md) 第 2 节迁移表同步更新 Data Capture 行状态。
- 改 Summary **processed amount** 精度 / rate / ±0.05 门槛时：同步更新 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」、后端 `SummaryAmountFormat`、前端 `summaryRowAmount.js`（及 Submit / 模板写入路径）。

---

## 33. Login → Permission → 各业务页面功能说明

> 原始独立文件：`docs/login-to-business-pages.md`（内容已合并于此；原文件已改为跳转说明）


> 基于当前 `backend/` 代码整理，描述**已实现**行为，并标注缺口。  
> 前端路由名与 `SessionUser.permissions` 小写 code 对应（如 `process` ↔ `PROCESS`）。

---

### 目录

1. [总览](#1-总览)
2. [登录与会话](#2-登录与会话)
3. [Permission 与侧边栏](#3-permission-与侧边栏)
4. [Domain 页面](#4-domain-页面)
5. [Admin 页面](#5-admin-页面)
6. [Account 页面](#6-account-页面)
7. [Auto Renew 页面](#7-auto-renew-页面)
8. [Ownership 页面](#8-ownership-页面)
9. [Process 页面](#9-process-页面)
10. [Transaction Payment 页面](#10-transaction-payment-页面)
11. [跨模块共性与缺口](#11-跨模块共性与缺口)
12. [文件索引](#12-文件索引)

---

### 1. 总览

```mermaid
flowchart TB
    subgraph auth [认证层]
        Login[POST /auth/login]
        CU[GET /auth/current-user]
        Redis[(Redis SessionUser)]
    end

    subgraph perm [权限层]
        PS[PermissionService]
        Perms[permissions: home, admin, account, ...]
    end

    subgraph pages [业务 API]
        Domain[/api/domain]
        Admin[/api/userlist]
        Account[/api/account + /api/currency]
        AutoRenew[/api/auto-renew]
        Ownership[/api/ownership]
        Process[/api/process]
    end

    Login --> PS --> Redis
    CU --> Redis
    Perms -->|前端路由守卫| pages
    pages -->|SecurityUtils.currentUser| Redis
```

**三类权限（勿混）：**

| 类型 | 作用 | 例子 |
|------|------|------|
| **侧边栏 permission** | 菜单是否显示 | `permissions` 含 `"account"` |
| **租户 feature_module** | 模块是否对该租户开放 | REPORT 需 GAME |
| **细粒度 ACL** | Admin 在该租户下可见范围 | `account_acl_mode` / `process_acl_mode` |

> 细粒度 ACL 多在 **Admin 授权** 时写入；**各业务 list API 大多未按 ACL 过滤**（见第 10 节）。

---

### 2. 登录与会话

#### 2.1 登录 `POST /auth/login`

| 参数 | 说明 |
|------|------|
| `tenant_code` | Group/Company 代码 |
| `password` | 密码 |
| `login_role` | `admin`（默认）或 `member` |
| `login_id` | Admin/Owner 用户名 |
| `account_id` | Member 账号 |

**身份识别顺序（`login_role=admin`）：**

1. **Admin**（`user` 表，`login_id`）
2. **Owner**（`owner` 表，`owner_code`）
3. **Member**（`login_role=member`，`account` 表）

**共同校验：**

- 租户 code 存在且 ACTIVE
- 该身份对 `tenant_code` 有访问权（`user_tenant_access` / owner 下属 tenant / member `account_tenant_access`）
- `expiration_date` 未过期

**登录后：**

1. 加载 `sessionTenant` 的 `featureModules`（`tenant_feature_module`）
2. `SessionUser.from(..., permissionService)` 计算 `permissions`
3. JWT + Cookie `ec_access_token`，`SessionUser` 存 Redis（key = `jti`）

**响应示例字段：**

```json
{
  "status": "success",
  "user_type": "user | owner | member",
  "redirect": "/dashboard | /user-secondary-password | /member",
  "tenant": { "id", "code", "type", ... },
  "login_tenant": { ... }
}
```

#### 2.2 会话续用

| API | 作用 |
|-----|------|
| `GET /auth/current-user` | 返回完整 `SessionUser`（含 `permissions`） |
| `POST /auth/switch-tenant` | 切换 `tenant_id`，重建 session |
| `GET /auth/tenant-accessible` | 可切换租户列表 |
| `POST /auth/verify-*-secondary-password` | 二级密码 |
| `POST /auth/logout` | 清 Redis + Cookie |

**鉴权机制：** `JwtAuthTokenFilter` 从 Cookie/Bearer 取 JWT → Redis 取 `SessionUser` → `SecurityUtils.currentUser()`。

**Spring Security：** 除 `/auth/login` 等公开路径外，`/api/**` 多为 `permitAll`；各 Service **自行**检查 `currentUser() != null`。

#### 2.3 SessionUser 关键字段

| 字段 | 含义 |
|------|------|
| `user_type` | `user` / `owner` / `member` |
| `tenant_id` / `tenant_code` | 当前会话租户 |
| `permissions` | 侧边栏模块，**小写**，如 `["home","admin","account","process"]` |
| `is_current_tenant_c168` | 是否 C168 |
| `tenant_has_game` / `tenant_has_bank` | 租户功能模块（Maintenance 子菜单、Process 路由；见 [`maintenance-navigation.md`](./maintenance-navigation.md)） |
| `read_only` | Admin 只读标记 |
| `needs_user_secondary` / `needs_owner_secondary` | 二级密码 |

**Member：** `permissions = []`，不走 Admin 侧边栏体系，登录后 `redirect=/member`。

---

### 3. Permission 与侧边栏

#### 3.1 数据模型

```
user_role (OWNER, ADMIN, MANAGER, ...)
    → user_role_permission
permission (HOME, DOMAIN, ADMIN, ACCOUNT, ...)
    → requires_feature_id? → feature_module
tenant → tenant_feature_module
```

#### 3.2 各 permission 与页面

| permission code | 前端 key | 对应页面/API | 备注 |
|-----------------|----------|--------------|------|
| HOME | `home` | Dashboard | 各角色基本都有 |
| DOMAIN | `domain` | Domain | **仅 C168 运行时注入**，不在角色默认种子 |
| ANNOUNCEMENTS | `announcements` | 公告 | C168 注入 |
| ADMIN | `admin` | Admin | `/api/userlist` |
| ACCOUNT | `account` | Account | `/api/account`、`/api/currency` |
| OWNERSHIP | `ownership` | Ownership | `/api/ownership` |
| PROCESS | `process` | Process | `/api/process` |
| DATACAPTURE | `datacapture` | Data Capture | 本文不展开 |
| PAYMENT | `payment` | Payment | `/api/transaction/*`（Search/History/Submit）；见 §10.6 |
| REPORT | `report` | Report | 需租户有 **GAME** 功能 |
| MAINTENANCE | `maintenance` | Maintenance | 本文不展开 |

#### 3.3 各角色默认是否含关键模块

| 角色 | DOMAIN | ADMIN | ACCOUNT | OWNERSHIP | PROCESS | Auto Renew* |
|------|--------|-------|---------|-----------|---------|-------------|
| OWNER / PARTNERSHIP / ADMIN | C168 注入 | ✅ | ✅ | ✅ | ✅ | 无独立 permission |
| MANAGER | C168 注入 | ✅ | ✅ | ❌ | ✅ | 同上 |
| SUPERVISOR | C168 注入 | ✅ | ✅ | ❌ | ✅ | 同上 |
| ACCOUNTANT | C168 注入 | ❌ | ✅ | ❌ | ✅ | 同上 |
| CUSTOMER_SERVICE | C168 注入 | ❌ | ✅ | ❌ | ✅ | 同上 |
| AUDIT | C168 注入 | ❌ | ❌ | ❌ | ❌ | 同上 |

\*Auto Renew 无单独 `permission` 记录，通常作为 **C168 / Domain 运营功能** 由前端路由控制，后端 `/api/auto-renew` 仅校验登录。

#### 3.4 解析逻辑（`PermissionServiceImpl`）

1. 按 `admin.roleId` 或 Owner 的 `OWNER` 角色查 `user_role_permission`
2. C168 租户额外加入 `DOMAIN`、`ANNOUNCEMENTS`
3. 过滤 `requires_feature_id`（如 REPORT 需 GAME）
4. 转小写写入 `SessionUser.permissions`

**前端：** `GET /auth/current-user` → 判断 `permissions.includes('domain')` 等 → 渲染菜单与路由。

---

### 4. Domain 页面

**前置：** `permissions` 含 `domain`（通常当前租户为 **C168**）。

**Base：** `/api/domain`  
**Service：** `DomainServiceImpl`

#### 4.1 API 一览

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?ownerId=` | Owner 下所有 Group/Company 列表 |
| POST | `/add` | 新建 Domain（Owner + Groups + Companies） |
| PUT | `/update` | 更新 Domain 骨架（增删改 tenant） |
| PUT | `/update-setting` | **仅保存设置**（方案 A） |
| POST | `/delete` | 删除 Owner（级联删 tenant） |
| POST | `/list-fee` | 全局续费价格（C168） |
| POST | `/add-fee` | 更新全局续费价格 |

#### 4.2 列表 `POST /list`

- 校验登录
- `findAllTenantsByOwner(ownerId)`
- 每个 `Tenant` 关联加载：
  - `feeShareAllocations` ← `tenant_fee_share_allocation`
  - `featureModules` ← `tenant_feature_module`

#### 4.3 新建 `POST /add`（`DomainDTO`）

1. 创建 `owner`（密码 BCrypt）
2. 遍历 `groups`：插入 GROUP tenant，在 **C168** 下自动建同名 ledger account
3. 遍历 `companies`：插入 COMPANY，挂 `parentId`，同样在 C168 建 account
4. **不**在此接口写 feature module / fee share（走 `update-setting`）

#### 4.4 更新骨架 `PUT /update`

- 更新 owner 信息
- 同步 groups/companies：已有则 update，没有则 insert，payload 中消失的 tenant 会 **delete**
- 为新 group/company 在 C168 补 account（若不存在）
- **不**替换 featureModules / feeShareAllocations

#### 4.5 保存设置 `PUT /update-setting`（方案 A）

写入内容：

| 字段 | 存储 |
|------|------|
| `code`, `expirationDate` | `tenant` 表 |
| `featureModules` | `tenant_feature_module`（先删后插） |
| `feeShareAllocations` | `tenant_fee_share_allocation`（先删后插） |

GROUP 若无 featureModules，自动确保默认 module（id=1）。

**Share % 业务语义**（`tenant_fee_share_allocation.owner_type`）：

- `PROFIT`（Profit 卡片）= C168 留存的 Domain fee，`owner_type` 固定为 `"owner"`。
- `SALES` / `CS` / `IT`（Commission 卡片）= 从当前公司应付款中扣给内部人员的佣金，`owner_type` 固定为 `"user"`。
- Profit 卡片在前端没有百分比输入框（只显示只读金额），其 `percentage` = `100 - (sales% + cs% + it%)`，多个 Profit 账号时按剩余份额均分（`domainHelpers.distributeProfitPercentages`）。保存时前端会即时算出该值再写入 `feeShareUiToSpring` 的结果，**不是** 用户手填的原始值。
- 后端 `DomainServiceImpl.validateAndPrepareFeeShareRows` 强制校验：`PROFIT` 只能是 `owner`，`SALES/CS/IT` 只能是 `user`（`group` + `partner_tenant_id` 仍保留给未来跨 tenant 分账场景，未与此规则冲突）。

#### 4.5.1 Domain Confirm "Charge on Save" → 写 `transactions`（2026-07-20）

> 触发点是 **Domain 主弹窗 Confirm**（`DomainFormModal.handleSubmit` → `syncAllTenantSettings` → 逐 tenant 调 `PUT /update-setting`），**不是** Company Settings 弹窗内的 Save。Company Settings 的 Save 只把 `apply_commission_payments_on_domain_save`（Charge on Save 开关）和 `selectedPeriod` 记进本地 tempCompany/tempGroup 状态，随 Domain Confirm 一起提交。

请求新增字段（`Tenant` 上的瞬态字段，**不落 `tenant` 表**，只用于这一次请求触发记账）：

| JSON 字段 | 说明 |
|---|---|
| `chargeDomainFeeOnConfirm` | `true` 才记账；前端只在 `apply_commission_payments_on_domain_save` 为真时才带上此字段 |
| `domainFeePeriod` | 续期周期 code（`7days`/`1month`/`3months`/`6months`/`1year`），用于查 `domain_list_fee_price` |

**业务规则**（`DomainFeeChargeService` / `DomainFeeChargeServiceImpl`）— 资金流方案 A：

- 付款方 = 当前 tenant（如 `OK1`），记账金额 = `domain_list_fee_price`（按 tenant_type + period）。
- 全部写在 **C168 ledger tenant**，币种固定 **MYR**（`currency.code = 'MYR'` under C168）。
- 全部使用 `transaction_type = PAYMENT`（Cr/Dr 台账，**不是** WIN/LOSE）：每条一行，`account_id` = 付款方账号（To，Cr/Dr 为负），`from_account_id` = 收款方账号（From，Cr/Dr 为正），`amount` 存正数。
- 付款方账号 = C168 ledger 下 `account_id` 等于付款 tenant code 的账号；C168（Profit）账号固定解析 code `"C168"`（fallback `"PROFIT"`）。

**固定两笔 + 按需 Commission（OK1 只扣一次全额）：**

1. **永远有** `PAY DOMAIN FEE`：付款方 → C168，金额 = Domain Fee **全额**（如 2000）。这是付款公司**唯一被扣**的一笔。
2. **有 Commission 时**：每个 `SALES/CS/IT`（`percentage > 0` 且有账号）一行 PAYMENT，**C168 → 对应 commission 账号**（从已收的 Fee 里再分出去），金额 = `domainFeeAmount × percentage / 100`，`description = "{SHARE_TYPE} COMMISSION FROM {付款方 code}"`。无该类型 / 0% / 无账号 → 不写。
3. **永远有** `NET PROFIT FROM {付款方 code}`：金额 = 全额 − Σ Commission（C168 最终留存净利润）。因钱已通过第 1 步进 C168、第 2 步打出佣金后自然留在 C168，此行记为 **C168 → C168**（列表可见，余额净变动为 0，避免二次扣款）。金额 ≤ 0 时不写。

**笔数：**

| 场景 | 笔数 |
|---|---|
| 无 Commission | 2（PAY DOMAIN FEE + NET PROFIT） |
| 1 / 2 / 3 种 Commission | 3 / 4 / 5 |

**例（OK1，Fee=2000，Sales 10% + IT 10%）：** `PAY DOMAIN FEE 2000`（OK1→C168）+ `SALES COMMISSION 200`（C168→Sales）+ `IT COMMISSION 200`（C168→IT）+ `NET PROFIT FROM OK1 1600`（C168→C168）→ 余额：OK1 −2000，Sales +200，IT +200，C168 +1600。

- 若该 tenant 从未配置过 Profit（`tenant_fee_share_allocation` 无 `PROFIT` 行）→ 直接 `BusinessException`，**拒绝记账**（Save 时也已要求必须有 Profit）。
- 记账成功后不需要显式"关闭"开关——`chargeDomainFeeOnConfirm` 从不落库，前端每次重新拉取 tenant 都不会带上一次的开关状态，天然默认关闭。

**改动文件**：`entity/Tenant.java`（新增两个瞬态字段）、`service/DomainFeeChargeService.java` + `impl/DomainFeeChargeServiceImpl.java`（新建）、`service/impl/DomainServiceImpl.java`（`updateTenantDetailsSetting` 末尾调用 + 保留 `BusinessException` 原始 message）、`dao/DomainListFeePriceDao.java` + Mapper（`findPriceByTenantTypeAndPeriod`）、`dao/UserDao.java` + `AccountMapper.xml`（`findAccountIdByTenantIdAndCode`）；前端 `pages/domain/domainApi.js`（`updateTenantSetting` / `syncAllTenantSettings` 新增两个字段）。

**遗留待办**：Sales/CS/IT 各 share_type 目前只保证「单个 share_type 总和 ≤100%」，未校验三者合计 + Profit 是否超过 100%（沿用 UI `computeShareTotals` 的既有 clamp 逻辑，Profit remainder 会被 clamp 到 0，不是新增限制）。

**复用入口（2026-07-22）：** Auto Renew `POST /api/auto-renew/approve` 调用同一套 `DomainFeeChargeService.chargeDomainFee(tenant, period)`（无 Charge on Save 开关）；到期日另按当前 `expiration_date` + period 延长。详见 §7。

#### 4.6 全局费用 `list-fee` / `add-fee`

- 表：`domain_list_fee_price` + `renewal_period`
- API 仍用 `DomainFeeSettingsDTO`（`company_period_prices` / `group_period_prices`）
- 内部：`DomainListFeePriceDao` 行读写 + `DomainFeeSettingsMapper` 转换

#### 4.7 删除

- 删 Owner → 先删其下所有 tenant
- **C168 Company 不可删**

---

### 5. Admin 页面

**前置：** `permissions` 含 `admin`。

**注意：** Controller 类名 `AdminController`，路径是 **`/api/userlist`**（历史命名，对应 `user` 表员工）。

**前端：** `Count-frontend/src/pages/userlist/`（`UserListPage.jsx`、`userListApi.js`、`userListLogic.js`）

#### 5.1 API 一览

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 租户下员工列表（Owner 登录时可能前置 Owner 影子行） |
| POST | `/get?user_id=&scope_tenant_id=` | 编辑弹窗详情（扁平 JSON） |
| POST | `/add` | 新建员工 |
| POST | `/update` | 更新员工 |
| POST | `/update-owner-profile` | Owner 本人资料（name/email/密码） |
| POST | `/updateStatus` | ACTIVE ↔ INACTIVE |
| POST | `/delete` | 删除 **INACTIVE** 员工 |

统一响应：`{ success, message, data }`；业务失败时多为 `success: false` 且 HTTP 200。

#### 5.2 数据模型（三层勿混）

| 层级 | 存储 | 说明 |
|------|------|------|
| **侧边栏 permission** | `user_role` + `user_role_permission` + `permission` | 按 **角色** 默认，**不**按人存 JSON |
| **租户授权** | `user_tenant_access` | 每人每租户一行；`account_acl_mode` / `process_acl_mode` |
| **细粒度 ACL** | `user_tenant_account_access` / `user_tenant_process_access` | `CUSTOM` 模式下的 account/process 白名单 |

**主表：** `user`（实体 `Admin`）+ `user_role.code`（ADMIN、MANAGER…）

**Owner 与 Admin 分离：** 域 Owner 在 **`owner` 表**，通过 `tenant.owner_id` 关联；**不在** `user_tenant_access` 中。Admin 列表中的 **Owner 影子行** 是展示用合成行，不是 `user` 表记录。

#### 5.3 列表 `POST /list`

**SQL：** `user` INNER JOIN `user_role` INNER JOIN `user_tenant_access`，`WHERE uta.tenant_id = ?`。

**嵌套响应（每条 `AdminDTO`）：**

| 字段 | 来源 |
|------|------|
| `admin` | `user`：id, loginId, name, email, roleCode, status, createdBy, lastLogin |
| `adminTenantAccess` | `user_tenant_access`：id, userId, tenantId, accountAclMode, processAclMode |
| `isOwnerShadow` | 仅合成 Owner 行为 `true` |

**Owner 影子行（`prependOwnerShadowRowIfViewerIsOwner`）：**

同时满足时在列表 **最前** 插入一行：

1. 当前 session `user_type == owner`
2. `session.user_id == tenant.owner_id`（当前 `tenant_id` 对应租户）
3. `owner` 表有记录，且列表中尚未存在同 id 的 `admin` 行

该行：`admin` 来自 `owner`（loginId=`owner_code`，role=`OWNER`），`adminTenantAccess = null`，`isOwnerShadow = true`。

**前端映射：** `normalizeAdminListItem()` → `isOwnerShadow: item.isOwnerShadow || (role===owner && !tenantAccess)`；影子行排序优先（`shadowCmp`）。

#### 5.4 详情 `POST /get`

编辑弹窗调用：`user_id` + `scope_tenant_id`（当前列表 scope 的 tenant.id）。

**返回扁平 `AdminDTO`（非 list 嵌套结构）：**

```json
{
  "id": 2,
  "loginId": "JS",
  "name": "JS",
  "email": "js@example.com",
  "role": "ADMIN",
  "status": "active",
  "readOnly": true,
  "scopeTenantId": 2,
  "tenantAccessId": 5,
  "tenantIds": [1, 2, 11],
  "permissions": ["home", "admin", "account", "process", ...],
  "accountPermissions": null,
  "processPermissions": [{ "id": 3, "process_id": "P001" }],
  "isOwnerShadow": false
}
```

**普通员工逻辑：**

1. 校验 `user` 存在，且在 `scope_tenant_id` 有 `user_tenant_access`
2. `tenantIds` ← 该用户全部 `user_tenant_access.tenant_id`
3. `permissions` ← `user_role_permission`（小写 code）
4. ACL 回填约定（与前端 `applyEditDetail` 一致）：

| `account_acl_mode` / `process_acl_mode` | `accountPermissions` / `processPermissions` |
|----------------------------------------|---------------------------------------------|
| `ALL`（或 null） | `null` → 前端视为全选 |
| `CUSTOM` | 查 `user_tenant_*_access` 联表，项为 `{ id, account_id }` / `{ id, process_id }` |
| `NONE` | `[]` → 前端全不选 |

**Owner 详情（`tenant.owner_id == user_id`）：**

- 从 **`owner` 表** 加载（非 `user`）
- 仅 **Owner 本人** session 可读（`requireOwnerSessionForProfile`）
- `tenantIds=[]`，account/process=`null`，`permissions` 按 OWNER 角色模板

#### 5.5 新建 `POST /add`

**请求体：** 扁平 JSON（`AdminDTO` 顶层字段），主要字段：

| 字段 | 说明 |
|------|------|
| `loginId`, `name`, `email`, `password`, `secondaryPassword` | 必填校验；密码 BCrypt |
| `role` | 映射 `user_role.code`（如 `customer service` → `CUSTOMER_SERVICE`） |
| `status` | 默认 `ACTIVE` |
| `readOnly` | 默认 `true` |
| `tenantIds` | 授权租户 id 列表（Group/Company picker） |
| `accountPermissions` / `processPermissions` | 勾选列表；**空数组 → ALL**，非空 → **CUSTOM** |
| `permissions` | 前端可能发送；**后端不持久化**（侧边栏仍按 role） |

**流程（`@Transactional`）：**

1. `insertAdmin` → `user`
2. 对每个 `tenantId`：`upsertTenantAccess` → `user_tenant_access`
3. `replaceAccountAcl` / `replaceProcessAcl` → 先删后插白名单行

**响应：** 嵌套 `{ admin, adminTenantAccess }`（当前 scope 对应的主 access）。

#### 5.6 更新 `POST /update`

**必填：** `id`, `scopeTenantId`；可选 `tenantAccessId`。

**与 add 相同字段；** `loginId` 不可改（沿用库中值）。

**租户同步：**

- 传 `tenantIds`：删除不在列表中的 `user_tenant_access`，再 upsert 列表内各租户 + ACL
- 未传 `tenantIds`：仅更新 **scopeTenantId** 对应的一条 access 与 ACL

**校验：** 用户须在 scope 租户存在；email 去重；`loadExistingAdmin` 校验存在性。

#### 5.7 Owner 资料 `POST /update-owner-profile`

用于 Admin 页编辑 **Owner 影子行**（替代原 PHP `userlist_api.php`）。

| 条件 | 说明 |
|------|------|
| session | `user_type == owner` 且 `user_id == dto.id` |
| 可改字段 | `name`, `email`, `password`, `secondaryPassword` |
| 实现 | 委托 `DomainService.updateOwnerDetails`（写 `owner` 表） |

**响应：** 嵌套 list 行（`isOwnerShadow: true`）。

#### 5.8 切换状态 `POST /updateStatus`

**Body：** `{ "id": userId, "scopeTenantId": tenantId }`

- 不能 toggle **自己**
- 不能 toggle **`tenant.owner_id`**（Owner 影子行）
- 须在 scope 租户有 `user_tenant_access`
- ACTIVE ↔ INACTIVE

#### 5.9 删除 `POST /delete`

**Body：** `{ "id", "scopeTenantId" }`（与 Account 页对齐）

1. 不能删自己、不能删 **tenant Owner**
2. 目标须为 **INACTIVE**
3. 先 `deleteTenantAccessByUserIdAndTenantId`（ACL 随 FK CASCADE）
4. 再 `deleteAdminByIdAndStatus(id, INACTIVE)` 删 `user` 行

> 若存在 `submitted_processes.user_id` 等无 CASCADE 引用，删除可能失败。

#### 5.10 Owner 影子行 — 前后端行为

```mermaid
flowchart LR
    subgraph list [Owner 登录 + /list]
        A[user 员工行]
        B[owner 合成行 isOwnerShadow]
        B --> C[置顶展示]
    end
    subgraph ui [列表操作]
        C --> D[Edit ✅ Owner 本人]
        C --> E[Status ❌]
        C --> F[Delete ❌]
    end
    subgraph edit [编辑保存]
        D --> G[/get owner 表]
        D --> H[/update-owner-profile]
    end
```

| 能力 | 规则 |
|------|------|
| 谁可见影子行 | 仅 **Owner 登录**且为当前租户 `owner_id` |
| Edit | 仅 `currentUserRole === owner'`；弹窗仅 name/email/密码（+ C168 二级密码） |
| Toggle status | 前端 `canToggleStatus=false`；后端 `assertNotTenantOwner` |
| Delete | 前端 `canDelete=false`；批量删除过滤 shadow；后端拒绝 owner_id |
| Permission 勾选 | UI 锁定；展示 OWNER 角色模板 |

**前端关键函数：** `computeRowCapabilities`、`rowIsOwnerShadow`、`getUserEditFieldLocks`、`fetchAdminDetailByUserId`、`updateAdminOwnerProfile`。

#### 5.11 Admin 与登录的关系

- **Admin 员工：** `AuthServiceImpl` → `user` + `user_tenant_access` 决定可登哪些 `tenant_code`
- **Owner：** `owner` + `tenant.owner_id`，**不**走 `user_tenant_access`
- `account_acl_mode` / `process_acl_mode` 设计为限制该员工在租户下的 Account/Process 可见范围；**Account/Process list API 尚未按 ACL 过滤**（见第 10 节）

#### 5.12 缺口与遗留

| 项 | 状态 |
|----|------|
| HTTP CRUD + get + Owner profile | ✅ 已实现 |
| 前端 Admin 页接 Spring | ✅ 已实现（Owner 编辑已脱离 PHP） |
| 请求体 `permissions` 按人持久化 | ❌ 仍按 role；前端传的 `permissions` 被忽略 |
| API 层校验 `permissions` 含 `admin` | ❌ 仅校验登录 |
| Account/Process 列表按 Admin ACL 过滤 | ❌ |
| Admin 列表对 **Admin 角色** 展示 Owner 行 | ❌ 仅 Owner 登录时注入 |

---

### 6. Account 页面

**前置：** `permissions` 含 `account`。

**主 API：** `/api/account`（`UserController` → `UserServiceImpl`）  
**辅助 API：** `/api/currency`（币别与账户币别关联）

#### 6.1 Account API `/api/account`

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 租户下 ledger 账户列表 |
| POST | `/add` | 新建账户 |
| POST | `/update` | 更新账户 |
| POST | `/updateStatus` | ACTIVE ↔ INACTIVE |
| POST | `/delete` | 仅 INACTIVE 可删 |
| POST | `/link` | 建立账户关联 |
| DELETE | `/link/{id}` | 删单条 link |
| DELETE | `/link/account/{accountId}` | 删某账户全部 link |
| DELETE | `/link/pair` | 按 pair 删 link |
| GET | `/link/list` | 某账户的关联账户 |
| GET | `/link/all` | 含自身的关联列表 |
| PUT | `/link` | 更新 link（先删后建） |

#### 6.2 账户模型要点

- 表：`account`（实体 `User`）+ `account_tenant_access`
- **role**：ledger 角色（CAPITAL, AGENT, MEMBER, DEBTOR…）
- 新建时：写 account、tenant access、**currency 关联**（`CurrencyService.insertAccountCurrency`）
- 不能 toggle **自己的** status

#### 6.3 Currency API `/api/currency`（Account 页常用）

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 租户币别列表 |
| POST | `/add` | 新增币别 |
| POST | `/delete` | 删除币别 |
| POST | `/available` | 账户可选币别（含已选标记） |
| POST | `/account/linked-accounts` | 某币别下账户关联配置 |
| POST | `/account/linked-accounts-update` | 批量保存账户-币别 |

#### 6.4 账户关联（`account_link`）

- 支持 **BIDIRECTIONAL** / **UNIDIRECTIONAL**
- 同一租户内、两端账户须存在
- 不能 link 自己

---

### 7. Auto Renew 页面

**前置：** 无独立 `permission`；实践中为 **C168 运营**功能，前端常从 Domain 入口进入。后端只校验登录。

**Base：** `/api/auto-renew`

#### 7.1 API

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list` | 列表 / 统计 / pending 数 |
| POST | `/reject` | 拒绝续费申请 |
| POST | `/approve` | 通过续费：写 Domain Fee 交易 + 从**当前**到期日延长 period |

**`/list` 请求体字段：**

| 字段 | 说明 |
|------|------|
| `status` | `pending` / `approved` / `rejected` / `all` |
| `entity_type` | `company` 或 `group`（页签） |
| `date_from` / `date_to` | 非 pending 时按处理日期过滤 |
| `action=pending_count` | 只返回全局 pending 总数 |

**`/approve` 请求体：**

| 字段 | 说明 |
|------|------|
| `request_id` | 必填，pending 申请 id |
| `period` | 必填：`7days` / `1month` / `3months` / `6months` / `1year` |

**Approve 业务（与 Domain Charge on Save 同账，无开关）：**

1. 校验 pending + period；查 `domain_list_fee_price`（按 tenant_type + period）
2. 新到期日 = **当前** `tenant.expiration_date` + period（方案 A；无到期日则从今天起算）
3. 调用 `DomainFeeChargeService.chargeDomainFee(tenant, period)`：读已存 Share %，写 `PAY DOMAIN FEE` / `{SALES\|CS\|IT} COMMISSION FROM {code}` / `NET PROFIT FROM {code}`（须已有 Profit 行，否则拒绝）
4. 更新 `tenant.expiration_date`；申请标 `approved`，并写入 `period` / `price` / `new_expiration_date` / `processed_by`
5. 同一 `@Transactional`：记账失败则到期日与状态都不改

无 Charge on Save 开关；Comm 只预先存分成，Approve 触发记账。

#### 7.2 列表逻辑（`AutoRenewServiceImpl.getAutoRenewList`）

1. `syncWindowRequests(30)` — 扫描 30 天内到期 ACTIVE tenant，写入 `tenant_auto_renew_request`（`INSERT IGNORE`）
2. 按 status / tenant_type / 日期查列表
3. 加载 C168 下 ACTIVE 账户 → `accounts`（供审批选 from/to）
4. 每行计算：
   - `default_to_account_id` → C168 账户 code=`C168`
   - `default_from_account_id` → 匹配 `companyCode` 或 `ownerCode_companyCode`
   - `can_approve` / `can_delete`
5. `fee_settings` ← `domainService.findDomainFeeSettings()`（`domain_list_fee_price`）
6. `counts` / `tab_pending_counts` — 各状态数量与 Company/Group pending 徽章

#### 7.3 拒绝 `POST /reject`

- `request_id` 必填
- 仅 `pending` 可拒绝
- 更新 `processed_by`

#### 7.4 缺口

- 列表不校验 `permissions`
- Delete / revert 交易回滚尚未接 Spring approve 写入的多笔 Domain Fee 行

---

### 8. Ownership 页面

**前置：** `permissions` 含 `ownership`（OWNER / PARTNERSHIP / ADMIN 等角色默认有）。

**Base：** `/api/ownership`

#### 8.1 API

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/list?tenant_id=&month=` | 股权结构列表（实时或历史） |
| GET | `/available-accounts?tenant_id=` | 可添加股东候选（PARTNER 角色账户） |
| POST | `/link-partner` | 关联 Partner（Owner 或 Group） |
| POST | `/batch-save-ownership` | 批量保存股权比例 |
| POST | `/update-parent-tenant` | 设置/清除 Company 的 parent Group |

`tenant_id` 支持数字 id 或 tenant code。

#### 8.2 列表 `GET /list`

- `month` 为空或当前月 → `tenant_ownership`（实时）
- 历史月 → `tenant_ownership_history`
- 响应 `meta.is_historical` / `effective_month`

#### 8.3 股东候选 `available-accounts`

- 租户下 PARTNER 等账户
- 排除已在 ownership 列表中的

#### 8.4 关联 Partner `link-partner`

- 按 `login_id` 解析为 **Owner** 或 **GROUP tenant**
- 冲突时返回 `status=conflict`
- 写入 `tenant_link` 等关联

#### 8.5 保存股权 `batch-save-ownership`

- body：`owners`（account_id、percentage…）、`month`、`retrofill_months`
- 当前月 → 更新实时表
- 历史月 → 写 history；支持追溯填充多月

#### 8.6 更新父级 `update-parent-tenant`

- `parent_code` 为空则清除 `tenant.parent_id`
- 否则按 code 查找 GROUP 设为 parent

---

### 9. Process 页面

**前置：** `permissions` 含 `process`。

**Base：** `/api/process`

#### 9.1 API

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 流程列表（含 descriptions、currency） |
| POST | `/add` | 新建流程 |
| POST | `/update-status` | ACTIVE ↔ INACTIVE |
| GET | `/list-description?tenant_id=` | 描述模板列表 |
| POST | `/add-description` | 新增描述（同名复用） |
| POST | `/delete-description` | 删除描述 |

#### 9.2 流程主表 `process`

| 列 | 说明 |
|----|------|
| `code` | 流程名（租户内唯一） |
| `currency_id` | 默认币别 |
| `description_ids` | JSON `[12,15]` |
| `schedule_days` | JSON 星期 `[1..7]` |
| `settings` | JSON：`remove_word`, `replace_word_from`, `replace_word_to` |
| `status` | ACTIVE / INACTIVE |

**新建 `add`：** `ProcessServiceImpl` 将 DTO 字段序列化为 JSON 后 insert。

**改状态：** 用 **`session.tenant_id`** 校验流程归属（不是请求里的 tenant_id）。

#### 9.3 Description 子模块

- `process_description`：租户级描述模板
- 建流程时通过 `descriptionIds` 引用

#### 9.4 缺口

- 无 edit/delete 整条 process（仅 status）
- `user_tenant_process_access` **未用于过滤列表**
- `submitted_processes`（Data Capture）无 API

---

### 10. Transaction Payment 页面

**前置：** `permissions` 含 `payment`。

**前端：** `Count-frontend/src/pages/transaction/`（`TransactionPaymentPage.jsx`、`transactionApi.js`）

**Base：** `/api/transaction`

#### 10.1 API 一览（2026-07-22）

| 方法 | 路径 | 功能 | 状态 |
|------|------|------|------|
| POST | `/search` | 主列表（BP Win/Loss + Payment Cr/Dr 合并） | ✅ Spring |
| POST | `/history` | 单账户 Payment History | ✅ Spring |
| POST | `/submit` | 手动 **PAYMENT / CLAIM / CLEAR / CONTRA / ADJUSTMENT / PROFIT / RATE** | ✅ Spring |

统一响应：`{ success, message, data }`；业务失败时多为 `success: false` 且 HTTP 200。

#### 10.2 手动 PAYMENT Submit

**账户方向（与 Domain Fee、列表 Cr/Dr 一致）**

| UI | DB 字段 | 含义 |
|----|---------|------|
| **To Account** | `account_id` | 付款方（Cr/Dr **−**） |
| **From Account** | `from_account_id` | 收款方（Cr/Dr **+**） |

**`POST /submit` 请求体（camelCase）**

| 字段 | 说明 |
|------|------|
| `tenantId` | 当前公司 `tenant.id` |
| `transactionType` | 默认 `PAYMENT`；transfer：`PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`；或 `ADJUSTMENT` / `PROFIT` / `RATE` |
| `transactionDate` | `dd/MM/yyyy` 或 `yyyy-MM-dd`；可省略 → 当天 |
| `toAccountId` / `fromAccountId` | `account.id`，须不同 |
| `currencyCode` 或 `currencyId` | 租户币别；两账户均须在 `account_currency` 启用 |
| `amount` | 正数 |
| `remark` | 可选 |

写入 `transactions`：`PAYMENT`、`approval_status=APPROVED`、`bank_process_posted_id=NULL`。提交后 Search / History 自动计入 Cr/Dr（与 Domain Fee 同路径）。

**History 展示（收款方 / 付款方）：**

| Type | 收款方 Description | 付款方 Description | Id Product |
|------|-------------------|-------------------|------------|
| PAYMENT | `PAYMENT TO {付款方}` | `PAYMENT FROM {收款方}` | `PAYMENT` |
| CLAIM | `CLAIM TO {付款方}` | `CLAIM FROM {收款方}` | `CLAIM` |
| CLEAR | `CLEAR TO {付款方}` | `CLEAR FROM {收款方}` | `CLEAR` |
| CONTRA | `CONTRA TO {付款方}` | `CONTRA FROM {收款方}` | `CONTRA` |
| ADJUSTMENT | —（仅 To） | — | `ADJUSTMENT` |
| PROFIT | `PROFIT TO {付款方}` | `PROFIT FROM {收款方}` | `PROFIT` |
| RATE | `EXCH RATE {rate} {ccy1} {amt} > {ccy2} \| TO {付款方}` | `EXCH RATE {rate} {ccy1} {amt} > {ccy2} \| FROM {收款方}` | `RATE` |

**ADJUSTMENT：** 仅 `toAccountId`；signed `amount` → **Win/Loss**（非 Cr/Dr）；`description = ADJUSTMENT - WIN/LOSS`。

**PROFIT：** `fromAccountId` + `toAccountId`；正数 `amount` → **Win/Loss**（From + / To −）；History desc 派生；Id Product=`PROFIT`。

**RATE：** … Fee 份额仅 middleman **+Win/Loss**（不对 leg2 付款方记 −WL，因手续费已含在第一币金额）；Rate Multiplier 仍 To−/From+。

**CONTRA Submit** 与 PAYMENT 相同：即时 `APPROVED` 进 Cr/Dr（无 pending Contra Inbox）。

**前端路由：** `transactionApi.submitTransaction` — `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`/`ADJUSTMENT`/`PROFIT`/`RATE` → Spring JSON。

**后端：** `TransactionSubmitServiceImpl`；详见 `docs/frontend-springboot-migration.md` §11.7。

---

### 11. 跨模块共性与缺口

#### 11.1 通用模式

| 模式 | 说明 |
|------|------|
| 登录检查 | 多数 Service 调 `SecurityUtils.currentUser()` |
| 响应格式 | `{ success, message, data }`（少数用 `status`） |
| tenant_id | 多由前端传 `session.tenant_id`，**多数 API 不强制一致** |
| Permission | **侧边栏层**生效；**API 层一般不校验** `permissions` 是否含对应模块 |

#### 11.2 Domain ↔ Account 联动

- Domain `add`/`update` 会在 **C168** 自动创建与 group/company code 同名的 ledger account（`createAccountTenantInC168`）
- Auto Renew 审批依赖这些 C168 账户作 from/to

#### 11.3 Domain ↔ Auto Renew 联动

- Auto Renew 列表附带 `fee_settings`（`domain_list_fee_price`）
- 租户到期触发 `tenant_auto_renew_request`

#### 11.4 Admin ACL 写入 vs 业务读取

| ACL | 写入 | 业务 API 是否过滤 |
|-----|------|-------------------|
| `account_acl_mode` + `user_tenant_account_access` | Admin add/update | ❌ Account list 未过滤 |
| `process_acl_mode` + `user_tenant_process_access` | Admin add/update | ❌ Process list 未过滤 |

#### 11.5 主要缺口汇总

- API 层缺少 permission 模块校验（含 Admin `/api/userlist`）
- Admin 请求体 `permissions` 未按人持久化（仅 role 模板）
- Auto Renew Delete / 交易回滚未对接多笔 Domain Fee
- Process / Account 缺 ACL 过滤
- Member 用户无侧边栏 permissions，不走上述 Admin 页面体系

---

### 12. 文件索引

```
认证
  controller/AuthController.java
  service/impl/AuthServiceImpl.java
  security/SessionUser.java, JwtAuthTokenFilter.java, AuthTokenStore.java

权限
  service/impl/PermissionServiceImpl.java
  dao/PermissionDao.java
  mybatis/PermissionMapper.xml

Domain
  controller/DomainController.java
  service/impl/DomainServiceImpl.java
  dao/DomainDao.java, DomainListFeePriceDao.java
  mybatis/DomainMapper.xml, DomainListFeePriceMapper.xml

Admin
  controller/AdminController.java          → /api/userlist
  service/impl/AdminServiceImpl.java
  dto/AdminDTO.java
  dao/AdminDao.java, DomainDao.java, TenantDao.java
  mybatis/AdminMapper.xml
  前端 Count-frontend/src/pages/userlist/
    UserListPage.jsx, userListApi.js, userListLogic.js, components/UserModal.jsx

Account
  controller/UserController.java           → /api/account
  controller/CurrencyController.java       → /api/currency
  service/impl/UserServiceImpl.java
  service/impl/CurrencyServiceImpl.java

Auto Renew
  controller/AutoRenewController.java
  service/impl/AutoRenewServiceImpl.java
  dao/AutoRenewDao.java
  mybatis/AutoRenewMapper.xml

Ownership
  controller/TenantOwnershipController.java
  service/impl/TenantOwnershipServiceImpl.java
  dao/TenantOwnershipDao.java

Process
  controller/ProcessController.java
  service/impl/ProcessServiceImpl.java
  dao/ProcessDao.java
  mybatis/ProcessMapper.xml

Transaction (BP Win/Loss + Domain Payment Cr/Dr + 手动 PAYMENT Submit, 2026-07-22)
  controller/TransactionController.java            → POST /api/transaction/search + /history + /submit
  service/impl/TransactionSearchServiceImpl.java
  service/impl/TransactionHistoryServiceImpl.java
  service/TransactionSubmitService.java
  service/impl/TransactionSubmitServiceImpl.java
  dto/TransactionDTO.java                         # Search/History + SubmitRequest/SubmitResult
  dao/TransactionDao.java
  mybatis/TransactionMapper.xml                   # aggregate* + history lines + insert

Account role UPLINE 移除 (2026-07-20)
  sql/migrate_upline_role_to_supplier.sql
  service/impl/UserServiceImpl.java             # 白名单无 UPLINE；写入 normalize → SUPPLIER
```
