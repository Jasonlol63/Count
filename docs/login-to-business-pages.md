# Login → Permission → 各业务页面功能说明

> 基于当前 `backend/` 代码整理，描述**已实现**行为，并标注缺口。  
> 前端路由名与 `SessionUser.permissions` 小写 code 对应（如 `process` ↔ `PROCESS`）。

---

## 目录

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

## 1. 总览

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

## 2. 登录与会话

### 2.1 登录 `POST /auth/login`

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

### 2.2 会话续用

| API | 作用 |
|-----|------|
| `GET /auth/current-user` | 返回完整 `SessionUser`（含 `permissions`） |
| `POST /auth/switch-tenant` | 切换 `tenant_id`，重建 session |
| `GET /auth/tenant-accessible` | 可切换租户列表 |
| `POST /auth/verify-*-secondary-password` | 二级密码 |
| `POST /auth/logout` | 清 Redis + Cookie |

**鉴权机制：** `JwtAuthTokenFilter` 从 Cookie/Bearer 取 JWT → Redis 取 `SessionUser` → `SecurityUtils.currentUser()`。

**Spring Security：** 除 `/auth/login` 等公开路径外，`/api/**` 多为 `permitAll`；各 Service **自行**检查 `currentUser() != null`。

### 2.3 SessionUser 关键字段

| 字段 | 含义 |
|------|------|
| `user_type` | `user` / `owner` / `member` |
| `tenant_id` / `tenant_code` | 当前会话租户 |
| `permissions` | 侧边栏模块，**小写**，如 `["home","admin","account","process"]` |
| `is_current_tenant_c168` | 是否 C168 |
| `tenant_has_game` / `tenant_has_bank` | 租户功能模块 |
| `read_only` | Admin 只读标记 |
| `needs_user_secondary` / `needs_owner_secondary` | 二级密码 |

**Member：** `permissions = []`，不走 Admin 侧边栏体系，登录后 `redirect=/member`。

---

## 3. Permission 与侧边栏

### 3.1 数据模型

```
user_role (OWNER, ADMIN, MANAGER, ...)
    → user_role_permission
permission (HOME, DOMAIN, ADMIN, ACCOUNT, ...)
    → requires_feature_id? → feature_module
tenant → tenant_feature_module
```

### 3.2 各 permission 与页面

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

### 3.3 各角色默认是否含关键模块

| 角色 | DOMAIN | ADMIN | ACCOUNT | OWNERSHIP | PROCESS | Auto Renew* |
|------|--------|-------|---------|-----------|---------|-------------|
| OWNER / PARTNERSHIP / ADMIN | C168 注入 | ✅ | ✅ | ✅ | ✅ | 无独立 permission |
| MANAGER | C168 注入 | ✅ | ✅ | ❌ | ✅ | 同上 |
| SUPERVISOR | C168 注入 | ✅ | ✅ | ❌ | ✅ | 同上 |
| ACCOUNTANT | C168 注入 | ❌ | ✅ | ❌ | ✅ | 同上 |
| CUSTOMER_SERVICE | C168 注入 | ❌ | ✅ | ❌ | ✅ | 同上 |
| AUDIT | C168 注入 | ❌ | ❌ | ❌ | ❌ | 同上 |

\*Auto Renew 无单独 `permission` 记录，通常作为 **C168 / Domain 运营功能** 由前端路由控制，后端 `/api/auto-renew` 仅校验登录。

### 3.4 解析逻辑（`PermissionServiceImpl`）

1. 按 `admin.roleId` 或 Owner 的 `OWNER` 角色查 `user_role_permission`
2. C168 租户额外加入 `DOMAIN`、`ANNOUNCEMENTS`
3. 过滤 `requires_feature_id`（如 REPORT 需 GAME）
4. 转小写写入 `SessionUser.permissions`

**前端：** `GET /auth/current-user` → 判断 `permissions.includes('domain')` 等 → 渲染菜单与路由。

---

## 4. Domain 页面

**前置：** `permissions` 含 `domain`（通常当前租户为 **C168**）。

**Base：** `/api/domain`  
**Service：** `DomainServiceImpl`

### 4.1 API 一览

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?ownerId=` | Owner 下所有 Group/Company 列表 |
| POST | `/add` | 新建 Domain（Owner + Groups + Companies） |
| PUT | `/update` | 更新 Domain 骨架（增删改 tenant） |
| PUT | `/update-setting` | **仅保存设置**（方案 A） |
| POST | `/delete` | 删除 Owner（级联删 tenant） |
| POST | `/list-fee` | 全局续费价格（C168） |
| POST | `/add-fee` | 更新全局续费价格 |

### 4.2 列表 `POST /list`

- 校验登录
- `findAllTenantsByOwner(ownerId)`
- 每个 `Tenant` 关联加载：
  - `feeShareAllocations` ← `tenant_fee_share_allocation`
  - `featureModules` ← `tenant_feature_module`

### 4.3 新建 `POST /add`（`DomainDTO`）

1. 创建 `owner`（密码 BCrypt）
2. 遍历 `groups`：插入 GROUP tenant，在 **C168** 下自动建同名 ledger account
3. 遍历 `companies`：插入 COMPANY，挂 `parentId`，同样在 C168 建 account
4. **不**在此接口写 feature module / fee share（走 `update-setting`）

### 4.4 更新骨架 `PUT /update`

- 更新 owner 信息
- 同步 groups/companies：已有则 update，没有则 insert，payload 中消失的 tenant 会 **delete**
- 为新 group/company 在 C168 补 account（若不存在）
- **不**替换 featureModules / feeShareAllocations

### 4.5 保存设置 `PUT /update-setting`（方案 A）

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

### 4.5.1 Domain Confirm "Charge on Save" → 写 `transactions`（2026-07-20）

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

### 4.6 全局费用 `list-fee` / `add-fee`

- 表：`domain_list_fee_price` + `renewal_period`
- API 仍用 `DomainFeeSettingsDTO`（`company_period_prices` / `group_period_prices`）
- 内部：`DomainListFeePriceDao` 行读写 + `DomainFeeSettingsMapper` 转换

### 4.7 删除

- 删 Owner → 先删其下所有 tenant
- **C168 Company 不可删**

---

## 5. Admin 页面

**前置：** `permissions` 含 `admin`。

**注意：** Controller 类名 `AdminController`，路径是 **`/api/userlist`**（历史命名，对应 `user` 表员工）。

**前端：** `Count-frontend/src/pages/userlist/`（`UserListPage.jsx`、`userListApi.js`、`userListLogic.js`）

### 5.1 API 一览

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

### 5.2 数据模型（三层勿混）

| 层级 | 存储 | 说明 |
|------|------|------|
| **侧边栏 permission** | `user_role` + `user_role_permission` + `permission` | 按 **角色** 默认，**不**按人存 JSON |
| **租户授权** | `user_tenant_access` | 每人每租户一行；`account_acl_mode` / `process_acl_mode` |
| **细粒度 ACL** | `user_tenant_account_access` / `user_tenant_process_access` | `CUSTOM` 模式下的 account/process 白名单 |

**主表：** `user`（实体 `Admin`）+ `user_role.code`（ADMIN、MANAGER…）

**Owner 与 Admin 分离：** 域 Owner 在 **`owner` 表**，通过 `tenant.owner_id` 关联；**不在** `user_tenant_access` 中。Admin 列表中的 **Owner 影子行** 是展示用合成行，不是 `user` 表记录。

### 5.3 列表 `POST /list`

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

### 5.4 详情 `POST /get`

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

### 5.5 新建 `POST /add`

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

### 5.6 更新 `POST /update`

**必填：** `id`, `scopeTenantId`；可选 `tenantAccessId`。

**与 add 相同字段；** `loginId` 不可改（沿用库中值）。

**租户同步：**

- 传 `tenantIds`：删除不在列表中的 `user_tenant_access`，再 upsert 列表内各租户 + ACL
- 未传 `tenantIds`：仅更新 **scopeTenantId** 对应的一条 access 与 ACL

**校验：** 用户须在 scope 租户存在；email 去重；`loadExistingAdmin` 校验存在性。

### 5.7 Owner 资料 `POST /update-owner-profile`

用于 Admin 页编辑 **Owner 影子行**（替代原 PHP `userlist_api.php`）。

| 条件 | 说明 |
|------|------|
| session | `user_type == owner` 且 `user_id == dto.id` |
| 可改字段 | `name`, `email`, `password`, `secondaryPassword` |
| 实现 | 委托 `DomainService.updateOwnerDetails`（写 `owner` 表） |

**响应：** 嵌套 list 行（`isOwnerShadow: true`）。

### 5.8 切换状态 `POST /updateStatus`

**Body：** `{ "id": userId, "scopeTenantId": tenantId }`

- 不能 toggle **自己**
- 不能 toggle **`tenant.owner_id`**（Owner 影子行）
- 须在 scope 租户有 `user_tenant_access`
- ACTIVE ↔ INACTIVE

### 5.9 删除 `POST /delete`

**Body：** `{ "id", "scopeTenantId" }`（与 Account 页对齐）

1. 不能删自己、不能删 **tenant Owner**
2. 目标须为 **INACTIVE**
3. 先 `deleteTenantAccessByUserIdAndTenantId`（ACL 随 FK CASCADE）
4. 再 `deleteAdminByIdAndStatus(id, INACTIVE)` 删 `user` 行

> 若存在 `submitted_processes.user_id` 等无 CASCADE 引用，删除可能失败。

### 5.10 Owner 影子行 — 前后端行为

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

### 5.11 Admin 与登录的关系

- **Admin 员工：** `AuthServiceImpl` → `user` + `user_tenant_access` 决定可登哪些 `tenant_code`
- **Owner：** `owner` + `tenant.owner_id`，**不**走 `user_tenant_access`
- `account_acl_mode` / `process_acl_mode` 设计为限制该员工在租户下的 Account/Process 可见范围；**Account/Process list API 尚未按 ACL 过滤**（见第 10 节）

### 5.12 缺口与遗留

| 项 | 状态 |
|----|------|
| HTTP CRUD + get + Owner profile | ✅ 已实现 |
| 前端 Admin 页接 Spring | ✅ 已实现（Owner 编辑已脱离 PHP） |
| 请求体 `permissions` 按人持久化 | ❌ 仍按 role；前端传的 `permissions` 被忽略 |
| API 层校验 `permissions` 含 `admin` | ❌ 仅校验登录 |
| Account/Process 列表按 Admin ACL 过滤 | ❌ |
| Admin 列表对 **Admin 角色** 展示 Owner 行 | ❌ 仅 Owner 登录时注入 |

---

## 6. Account 页面

**前置：** `permissions` 含 `account`。

**主 API：** `/api/account`（`UserController` → `UserServiceImpl`）  
**辅助 API：** `/api/currency`（币别与账户币别关联）

### 6.1 Account API `/api/account`

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

### 6.2 账户模型要点

- 表：`account`（实体 `User`）+ `account_tenant_access`
- **role**：ledger 角色（CAPITAL, AGENT, MEMBER, DEBTOR…）
- 新建时：写 account、tenant access、**currency 关联**（`CurrencyService.insertAccountCurrency`）
- 不能 toggle **自己的** status

### 6.3 Currency API `/api/currency`（Account 页常用）

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 租户币别列表 |
| POST | `/add` | 新增币别 |
| POST | `/delete` | 删除币别 |
| POST | `/available` | 账户可选币别（含已选标记） |
| POST | `/account/linked-accounts` | 某币别下账户关联配置 |
| POST | `/account/linked-accounts-update` | 批量保存账户-币别 |

### 6.4 账户关联（`account_link`）

- 支持 **BIDIRECTIONAL** / **UNIDIRECTIONAL**
- 同一租户内、两端账户须存在
- 不能 link 自己

---

## 7. Auto Renew 页面

**前置：** 无独立 `permission`；实践中为 **C168 运营**功能，前端常从 Domain 入口进入。后端只校验登录。

**Base：** `/api/auto-renew`

### 7.1 API

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

### 7.2 列表逻辑（`AutoRenewServiceImpl.getAutoRenewList`）

1. `syncWindowRequests(30)` — 扫描 30 天内到期 ACTIVE tenant，写入 `tenant_auto_renew_request`（`INSERT IGNORE`）
2. 按 status / tenant_type / 日期查列表
3. 加载 C168 下 ACTIVE 账户 → `accounts`（供审批选 from/to）
4. 每行计算：
   - `default_to_account_id` → C168 账户 code=`C168`
   - `default_from_account_id` → 匹配 `companyCode` 或 `ownerCode_companyCode`
   - `can_approve` / `can_delete`
5. `fee_settings` ← `domainService.findDomainFeeSettings()`（`domain_list_fee_price`）
6. `counts` / `tab_pending_counts` — 各状态数量与 Company/Group pending 徽章

### 7.3 拒绝 `POST /reject`

- `request_id` 必填
- 仅 `pending` 可拒绝
- 更新 `processed_by`

### 7.4 缺口

- 列表不校验 `permissions`
- Delete / revert 交易回滚尚未接 Spring approve 写入的多笔 Domain Fee 行

---

## 8. Ownership 页面

**前置：** `permissions` 含 `ownership`（OWNER / PARTNERSHIP / ADMIN 等角色默认有）。

**Base：** `/api/ownership`

### 8.1 API

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/list?tenant_id=&month=` | 股权结构列表（实时或历史） |
| GET | `/available-accounts?tenant_id=` | 可添加股东候选（PARTNER 角色账户） |
| POST | `/link-partner` | 关联 Partner（Owner 或 Group） |
| POST | `/batch-save-ownership` | 批量保存股权比例 |
| POST | `/update-parent-tenant` | 设置/清除 Company 的 parent Group |

`tenant_id` 支持数字 id 或 tenant code。

### 8.2 列表 `GET /list`

- `month` 为空或当前月 → `tenant_ownership`（实时）
- 历史月 → `tenant_ownership_history`
- 响应 `meta.is_historical` / `effective_month`

### 8.3 股东候选 `available-accounts`

- 租户下 PARTNER 等账户
- 排除已在 ownership 列表中的

### 8.4 关联 Partner `link-partner`

- 按 `login_id` 解析为 **Owner** 或 **GROUP tenant**
- 冲突时返回 `status=conflict`
- 写入 `tenant_link` 等关联

### 8.5 保存股权 `batch-save-ownership`

- body：`owners`（account_id、percentage…）、`month`、`retrofill_months`
- 当前月 → 更新实时表
- 历史月 → 写 history；支持追溯填充多月

### 8.6 更新父级 `update-parent-tenant`

- `parent_code` 为空则清除 `tenant.parent_id`
- 否则按 code 查找 GROUP 设为 parent

---

## 9. Process 页面

**前置：** `permissions` 含 `process`。

**Base：** `/api/process`

### 9.1 API

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/list?tenant_id=` | 流程列表（含 descriptions、currency） |
| POST | `/add` | 新建流程 |
| POST | `/update-status` | ACTIVE ↔ INACTIVE |
| GET | `/list-description?tenant_id=` | 描述模板列表 |
| POST | `/add-description` | 新增描述（同名复用） |
| POST | `/delete-description` | 删除描述 |

### 9.2 流程主表 `process`

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

### 9.3 Description 子模块

- `process_description`：租户级描述模板
- 建流程时通过 `descriptionIds` 引用

### 9.4 缺口

- 无 edit/delete 整条 process（仅 status）
- `user_tenant_process_access` **未用于过滤列表**
- `submitted_processes`（Data Capture）无 API

---

## 10. Transaction Payment 页面

**前置：** `permissions` 含 `payment`。

**前端：** `Count-frontend/src/pages/transaction/`（`TransactionPaymentPage.jsx`、`transactionApi.js`）

**Base：** `/api/transaction`

### 10.1 API 一览（2026-07-22）

| 方法 | 路径 | 功能 | 状态 |
|------|------|------|------|
| POST | `/search` | 主列表（BP Win/Loss + Payment Cr/Dr 合并） | ✅ Spring |
| POST | `/history` | 单账户 Payment History | ✅ Spring |
| POST | `/submit` | 手动 **PAYMENT / CLAIM / CLEAR / CONTRA / ADJUSTMENT** | ✅ Spring |

统一响应：`{ success, message, data }`；业务失败时多为 `success: false` 且 HTTP 200。

### 10.2 手动 PAYMENT Submit

**账户方向（与 Domain Fee、列表 Cr/Dr 一致）**

| UI | DB 字段 | 含义 |
|----|---------|------|
| **To Account** | `account_id` | 付款方（Cr/Dr **−**） |
| **From Account** | `from_account_id` | 收款方（Cr/Dr **+**） |

**`POST /submit` 请求体（camelCase）**

| 字段 | 说明 |
|------|------|
| `tenantId` | 当前公司 `tenant.id` |
| `transactionType` | 默认 `PAYMENT`；transfer：`PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`；或 `ADJUSTMENT` |
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

**ADJUSTMENT：** 仅 `toAccountId`；signed `amount` → **Win/Loss**（非 Cr/Dr）；`description = ADJUSTMENT - WIN/LOSS`。

**CONTRA Submit** 与 PAYMENT 相同：即时 `APPROVED` 进 Cr/Dr（Contra Inbox 审批 API 仍 PHP，未接）。

**前端路由：** `transactionApi.submitTransaction` — `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA`/`ADJUSTMENT` → Spring JSON；RATE 等仍 `submit_api.php`。

**后端：** `TransactionSubmitServiceImpl`；详见 `docs/frontend-springboot-migration.md` §11.7。

---

## 11. 跨模块共性与缺口

### 11.1 通用模式

| 模式 | 说明 |
|------|------|
| 登录检查 | 多数 Service 调 `SecurityUtils.currentUser()` |
| 响应格式 | `{ success, message, data }`（少数用 `status`） |
| tenant_id | 多由前端传 `session.tenant_id`，**多数 API 不强制一致** |
| Permission | **侧边栏层**生效；**API 层一般不校验** `permissions` 是否含对应模块 |

### 11.2 Domain ↔ Account 联动

- Domain `add`/`update` 会在 **C168** 自动创建与 group/company code 同名的 ledger account（`createAccountTenantInC168`）
- Auto Renew 审批依赖这些 C168 账户作 from/to

### 11.3 Domain ↔ Auto Renew 联动

- Auto Renew 列表附带 `fee_settings`（`domain_list_fee_price`）
- 租户到期触发 `tenant_auto_renew_request`

### 11.4 Admin ACL 写入 vs 业务读取

| ACL | 写入 | 业务 API 是否过滤 |
|-----|------|-------------------|
| `account_acl_mode` + `user_tenant_account_access` | Admin add/update | ❌ Account list 未过滤 |
| `process_acl_mode` + `user_tenant_process_access` | Admin add/update | ❌ Process list 未过滤 |

### 11.5 主要缺口汇总

- API 层缺少 permission 模块校验（含 Admin `/api/userlist`）
- Admin 请求体 `permissions` 未按人持久化（仅 role 模板）
- Auto Renew Delete / 交易回滚未对接多笔 Domain Fee
- Process / Account 缺 ACL 过滤
- Member 用户无侧边栏 permissions，不走上述 Admin 页面体系

---

## 12. 文件索引

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
