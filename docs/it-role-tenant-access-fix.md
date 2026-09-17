# IT 角色 · 进入公司页面报错 & 集团/关联公司列表缺失修复

> **范围**：① IT 登录后进入任意公司页面（如 Domain）报 "Failed to load domain data" 等错误；② IT 看不到 C168 集团及其关联公司列表。两处均已在后端修复。
> **前置阅读**：[`it-role-audit-log.md`](./it-role-audit-log.md)（IT 账号体系）、[`it-role-maintenance-mode-and-sidebar-fix.md`](./it-role-maintenance-mode-and-sidebar-fix.md)（IT sidebar 菜单项修复，本文档修的是更底层的租户访问链路）。
> **最后更新**：2026-09-17

---

## 背景

`SessionUser.fromItOperator()`（`backend/src/main/java/com/eazycount/security/SessionUser.java:270-300`）给 IT 会话的 `user_id` 故意设为 `null`——因为 IT 账号来自 `ItOperatorRegistry`（`it-operators.yml`），不是 owner/admin/member 表里的一行数据。这个设计本身没问题，但后端好几处校验/查询逻辑，长期以来都隐含假设"能走到这里的会话，`user_id` 一定不为空"，从没人给 IT 开过口子，于是 IT 一进公司页面就到处踩坑。

## 现象

1. IT 登录后点进任意公司（含 C168），打开 Domain 等需要"当前公司数据"的页面，页面顶部出现红色报错（如 "Failed to load domain data"），数据加载不出来。
2. 侧边栏/下拉里本该显示的 C168 集团及其关联公司列表，IT 账号下完全是空的。

## 根因（两处，各自独立）

### 1. 进入公司页面报错

链路：前端进入某公司 → 调用 `POST /auth/switch-tenant` → `AuthServiceImpl.switchSessionTenant`（`AuthServiceImpl.java:412-454`）→ 后续该公司下任意页面的接口都会调用 `AccessControlUtils.requireLoggedIn()` 校验会话。这条链路上有三个位置默认"未登录"：

- `AccessControlUtils.requireLoggedIn(SessionUser session)`（`util/AccessControlUtils.java:65-70`）：只要 `user_id == null` 就判定"未登录"——这是最大面的坑，Domain/Report/DataCapture 等几乎所有业务 Service 的每个方法开头都调这个检查，IT 一律被拒。
- `AuthServiceImpl.userCanAccessTenantId`（`AuthServiceImpl.java:656-677`）：`switch-tenant` 内部的权限校验，同样因为 `user_id == null` 直接返回 `false`，报 "No permission to access this company"。
- `AuthServiceImpl.rebuildSessionUserWithTenant`（`AuthServiceImpl.java:679-698`）：切换租户后重建会话身份，调用 `requireIdentity(userType, ...)`，而这个方法的 `switch` 里根本没有 `"it"` 分支，会抛 "Invalid identity type"。

### 2. 集团/关联公司列表缺失

链路：`GET /auth/accessible-tenants`、`GET /auth/tenant-by-code`（`AuthController.java:120`/`124`）都调用 `AuthServiceImpl.findAllTenantsByUserType(userType, user.user_id)`（`AuthServiceImpl.java:277-291`）。这个方法：
- 一进来就 `if (userId == null) throw "Invalid Login!"`——IT 必挂；
- 就算放过这一步，`switch` 里也只认 `owner`/`member`/`user` 三种身份，没有 `"it"` 分支，`TenantDao` 也从没有过"不按 owner/admin/member 过滤、返回全部租户"的查询方法。

也就是说，IT 的租户列表这块功能之前根本没写，不是回归 bug。

## 修复方案

**原则**：IT 是独立于 admin/user 权限体系之外的"无限制"身份（同 `fromItOperator()` 注释、`buildMenu()` 空权限=不受限的既有约定），所以这几处校验都改成"认 `role == "it"` 为已登录/已授权"，不再依赖 `user_id`。

1. **`AccessControlUtils.requireLoggedIn(SessionUser session)`**：判空条件从 `user_id == null` 改成 `user_id == null && !isItOperator(session.role)`。这是本次影响面最大的一处修复，直接让 IT 能通过 Domain/Report/DataCapture 等所有业务 Service 的登录态校验。
2. **`AuthServiceImpl.userCanAccessTenantId`**：加一个 IT 分支，跳过"按 userId 查已分配租户"的逻辑，直接查 `tenantDao.findTenantById(tenantId)` 判断租户存在且未过期即可放行——IT 没有"分配关系"这个概念，能访问任意有效租户。
3. **`AuthServiceImpl.rebuildSessionUserWithTenant`**：加一个 `"it"` 分支，用 `ItOperatorRegistry.findByUsername(current.login_id)`（跟 `login()` 里用的是同一个 registry）重新解析出 IT 身份，再走 `SessionUser.from(...)` 重建会话，不再经过只认 DB 身份的 `requireIdentity`。
4. **`AuthServiceImpl.findAllTenantsByUserType`**：`userId == null` 的强制校验改成只对非 IT 身份生效；`switch` 里加 `"it"` 分支，调用新增的 `TenantDao.findAllActiveTenantFeatures()`。
5. **`TenantDao.findAllActiveTenantFeatures()`（新增，`TenantDao.java` + `TenantMapper.xml`）**：不带任何 owner/admin/member 过滤条件，返回所有 `status = 'ACTIVE'` 且 code 非空的租户（含 GROUP 和普通公司），排序方式与 `findTenantFeaturesByOwnerId` 保持一致（GROUP 优先、按 code/id 排序）。

## 影响范围 / 回归说明

- `requireLoggedIn` 的改动只放宽了 IT 这一种身份，其余角色的 `user_id` 该有值还是有值，判断结果不变，无回归风险。
- `userCanAccessTenantId`/`rebuildSessionUserWithTenant`/`findAllTenantsByUserType` 的改动都是新增独立分支（`isItOperator` 判断或 `"it"` case），不改动 owner/member/user 原有分支的代码路径。
- `findAllActiveTenantFeatures` 是全新查询方法，不影响任何现有调用方。

## 已知缺口 / 后续

- 只做了后端修复 + `mvnw compile` 编译验证，尚未做真实浏览器端到端回归（IT 登录 → 进入多个不同公司 → 确认 Domain/Report/DataCapture 等页面数据都能正常加载、集团列表正确显示）。
- 如果后续还有其他 Service 直接用 `session.user_id` 做查询过滤（而不是只用来判断"是否登录"），要单独排查——`requireLoggedIn` 放行之后，这些地方拿到的 `user_id` 对 IT 来说仍然是 `null`，用来做 SQL 过滤会导致查不到数据而不是报错，需要具体页面具体确认。

## 参考文件

- `backend/src/main/java/com/eazycount/util/AccessControlUtils.java`
- `backend/src/main/java/com/eazycount/service/impl/AuthServiceImpl.java`
- `backend/src/main/java/com/eazycount/dao/TenantDao.java`
- `backend/src/main/resources/mybatis/TenantMapper.xml`
- `backend/src/main/java/com/eazycount/security/SessionUser.java`（`fromItOperator`，未改动，仅作为背景参考）
