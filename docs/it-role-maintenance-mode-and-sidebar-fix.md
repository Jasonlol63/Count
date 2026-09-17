# IT 角色 · 系统维护模式（踢人开关）与 Sidebar 公司属性修复

> **范围**：① 全局"踢人"维护开关（前后端均已完成）；② IT 登录后 sidebar 入口跟公司真实属性（bank/game/C168）脱节的 bug 修复（前后端均完成）；③ 维护模式期间拦截登录（凭证正确也不放行，前后端均完成）。
> **前置阅读**：[`it-role-audit-log.md`](./it-role-audit-log.md)（IT 账号体系、`@Audited` AOP 审计基础设施）。
> **前端设计文档**：`Count-frontend/docs/it-role-system-maintenance-mode.md`（开关 UI 放哪里、样式、登录页弹窗设计——本文档只写后端，不重复前端细节）。
> **最后更新**：2026-09-17

---

## 1. 系统维护模式（踢人开关）

### 1.1 需求

IT 需要一个"一键踢出所有在线用户"的开关，用于系统维护窗口。明确要求：**全局无差别，不分 tenant/公司，开关一开就直接踢，没有任何限制条件**。IT 账号本身不受影响。

### 1.2 设计决策

- **单例表，不建 key-value 通用设置表**——一开始讨论过做成通用 `system_settings`（key-value，方便以后塞其他全局配置如 version），但当下只有这一个确定要用的布尔开关，通用表会引入字符串解析（`"1"/"0"` vs `"true"/"false"`）这类没有必要的间接成本，所以采用跟 `platform_settings` 相同的**单例行模式**（固定 `id=1`，`PRIMARY KEY(id)`，app 永远只操作这一行，不新增行）。以后如果确定要加别的全局开关再重新评估要不要升级成 key-value 表。
- **不存 `enabled_by`/`enabled_at`/`disabled_by`/`disabled_at`**——最初设计里加了这几个审计字段，后来发现完全多余：切换动作本身贴了 `@Audited`，谁在什么时候把开关切到什么值，`audit_log` 表已经记了一份，没必要在这张表里重复维护第二份历史。表最终只有 `id` / `enabled` / `updated_at` 三列。
- **鉴权分两层，不混在一起**：
  - `SystemMaintenanceModeService.isEnabled()` **不做权限校验**——因为它有两个调用方，语境完全不同：`JwtAuthTokenFilter`（还没建立身份，没法做角色判断）和 IT 控制台的状态查询接口（由 Controller 自己在调用前做 `requireItOperator`）。把校验硬塞进这个方法里，会让"内部基础设施读取"和"面向 IT 的查询接口"两种语境互相干扰。
  - `setEnabled()` 内部做 `AccessControlUtils.requireItOperator(...)`——这是唯一的写入口，只会被 Controller 调用，不存在多语境问题，直接在 Service 里校验，跟 `AuditLogServiceImpl.search()/summary()` 的写法保持一致。
  - 这套鉴权完全走"新格式"（`isItOperator`/`requireItOperator`），不触碰任何旧的 `ADMIN_PAGE_MANAGER_ROLES`/hierarchy 体系。
- **不做 realtime 广播**——IT 计划后续把整套 realtime 机制用纯 Spring Boot 重做，本次功能范围明确排除 realtime，只做"下一次请求生效"：开关打开后，非 IT 用户的下一次任意 API 请求（包括页面刷新时前端 `fetchCurrentUser()`）会被当成未登录，走现有的"session 失效→跳登录页"逻辑，**不需要额外前端跳转代码**。已经打开、停在原地不发任何请求的页面不会立刻自己跳走，这是明确接受的过渡状态，等 realtime 重做后再补。
- **强制下线机制**：不扫描 Redis 里现有的 session key 逐个删除（`KEYS` 扫描在生产环境有性能顾虑），而是在 `JwtAuthTokenFilter` 里，每次请求从 Redis 命中 `SessionUser` 之后，多查一次维护开关状态（Redis 缓存，未命中才落到 DB），开关 ON 且角色不是 `it` 就直接不设置 `SecurityContext`。

### 1.3 数据库

新表 `system_maintenance_mode`，迁移脚本
[`migrate_add_system_maintenance_mode_table.sql`](../backend/src/main/resources/sql/migrate_add_system_maintenance_mode_table.sql)，已在本地 `testcount` 库执行验证（`id=1, enabled=0`）。**尚未同步进 `schema.sql`/`TABLE_MIGRATION.md`**——留到前端 UI 也做完、整个功能确认稳定之后再一并补录，避免文档跟代码来回改。

```sql
CREATE TABLE IF NOT EXISTS `system_maintenance_mode` (
    `id`         TINYINT UNSIGNED NOT NULL COMMENT 'Always 1 -- singleton row',
    `enabled`    TINYINT(1) NOT NULL DEFAULT 0,
    `updated_at` TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
```

### 1.4 后端分层（进度）

| 文件 | 职责 | 状态 |
|------|------|------|
| `migrate_add_system_maintenance_mode_table.sql` | 建表 + 种子行 | ✅（已跑 `testcount`） |
| `SystemMaintenanceModeDao.java` / `SystemMaintenanceModeMapper.xml` | 读/写单例行 | ✅ |
| `SystemMaintenanceModeService.java` / `SystemMaintenanceModeServiceImpl.java` | Redis 缓存 + DB 回退、`@Audited` 标注切换动作 | ✅ |
| `SystemMaintenanceModeController.java`（`/api/it/maintenance-mode`） | `GET` 查状态、`POST?enabled=` 切换，均 `requireItOperator` | ✅ |
| `JwtAuthTokenFilter.java` | 每次请求拦截判断 | ✅ |
| 前端 UI（Maintenance 通告页标题栏内的开关） | 见前端文档 | ✅ |
| Redis key | `ec:maintenance:enabled`（`"1"`/`"0"`），未命中回退 DB | ✅ |
| `AuthServiceImpl.requireNotUnderMaintenance()` | 登录时拦截，见 §3 | ✅ |

---

## 2. IT 登录 Sidebar 公司属性 bug 修复

### 2.1 现象

IT 账号登录任意公司（含 C168）后，sidebar 只显示 Home / Admin / Account / Transaction Payment / Maintenance，Report、Data Capture、Ownership、Domain/Announcement/Auto Renew 等入口全部缺失——且这个缺失**跟登录的公司实际有没有这些模块无关**，任何公司登录结果都一样。

### 2.2 根因（三处，互相独立）

1. **`SessionUser.fromItOperator()` 没有计算这家公司的真实属性**——对比 `fromAdmin`/`fromOwner`，两者都会拿 `featureModules` 调 `permissionService.hasGameModule(...)`/`hasBankModule(...)`，IT 分支之前直接不接收这两个参数，`tenant_has_game`/`tenant_has_bank` 写死 `false`。
2. **`buildMenu()` 把"空权限=不受限"和"空权限=啥都没有"两种语义混用**——IT 的 `permissions` 故意传空数组，是为了让前端 `hasFullPermissions(me)`（空数组=不受限）判定为 `true`，从而放行 Admin/Account/Maintenance/Home。但 `menu.report`/`menu.dataCapture` 走的是 `keys.contains(...)` 这条完全独立的逻辑，不认"空=不受限"这条规则，空数组在这里反而变成"两个都没有"，导致 Report/Data Capture 对 IT 永远隐藏，跟公司是否真的有这两个模块无关。
3. **前端另外三处硬编码角色白名单，压根没把 `"it"` 纳入**——`roleSupportsOwnershipPermission`（Ownership）、`userRoleAllowsC168Domain`（Domain/Announcement）、`userRoleAllowsC168AutoRenew`（Auto Renew）。这三处不是"公司属性没读到"，是角色字符串比对本身就没考虑过 IT 这个身份。

### 2.3 修复方案

**后端（[`SessionUser.java`](../backend/src/main/java/com/eazycount/security/SessionUser.java)）：**
- `fromItOperator` 签名加上 `featureModules`/`permissionService`，跟 `fromAdmin`/`fromOwner` 一样算出真实 `hasGame`/`hasBank`。调用链（`AuthController` 登录 → `LoginResultDTO.sessionFeatureModules` → `SessionUser.from`）本来就已经在给每种身份传这两个参数，只是 IT 分支之前没接而已，**不需要额外查询**。
- `buildMenu()` 的 `report`/`dataCapture` 判断加上"空列表 = 不受限"的语义，与 `hasFullPermissions` 保持一致：`report = keys.isEmpty() || keys.contains("report")`，`dataCapture = (keys.isEmpty() || keys.contains("datacapture")) && (hasGame || hasBank || isGroupLogin)`。

修完这两点，Report / Data Capture / Maintenance 的完整版 vs 受限版，会完全跟着登录的这家公司真实的 GAME/BANK 模块走——**不需要动前端一行代码**。

**前端（三处角色白名单各加一条独立分支，旧函数本体不动）：**
- [`sidebarPermissions.js`](../Count-frontend/src/utils/auth/sidebarPermissions.js:39)：`canAccessPermission` 的 Ownership 特判加 `&& !isItOperator(me)`。
- [`loginScope.js`](../Count-frontend/src/utils/company/loginScope.js)：新增内部 `isItOperatorRole(me)`（跟 `sidebarPermissions.js` 的 `isItOperator` 同逻辑，写成本地副本是为了避免两个文件互相 import 造成循环依赖），`canAccessC168DomainPages`/`canAccessC168AutoRenew` 的返回条件里加 `isItOperatorRole(me) ||`。
- **注意**：`isActiveCompanyContextC168(me)` 这条前置检查**没有跳过**——"是否正处于 C168 公司下"是公司真实形态的判断，不是权限判断，IT 一样要遵守；只有角色白名单那一行本身被放开。

### 2.4 影响范围 / 回归说明

- `buildMenu()` 的语义调整对 Owner/Admin 没有回归风险：只要账号的权限列表是非空的真实列表，`unrestricted || keys.contains(...)` 里 `unrestricted` 恒为 `false`，行为跟改之前完全一样；只有本来就传空列表的账号（目前只有 IT）行为会变。
- 三处前端白名单只新增 OR 分支，不改原白名单集合，非 IT 角色的判断结果不受影响。

---

## 3. 维护模式期间拦截登录

### 3.1 需求

用户被踢出后（或维护模式打开期间任何非 IT 用户）尝试重新登录，即使账号/密码/tenant_code 全部正确，
也不能真的登录进去，要让前端能弹出"系统维护中"的专属提示（而不是让 session 真的建立起来，或者只看
到一个含糊的登录失败）。IT 账号不受影响。

### 3.2 关键约束：不能因为维护模式而提前泄露状态

如果账号或密码本身是错的，必须继续返回"Username or password is incorrect"，**不能**在密码校验之前
就检查维护开关——否则一个还没验证身份的人，仅凭"系统提示维护中 vs 提示密码错"这两种不同响应，就能
反推出"维护模式当前是否开启"这个系统状态，属于不必要的信息泄露。所以检查点必须严格放在**密码校验
通过、tenant 过期校验也通过之后**，生成 session/写 last_login 之前。

### 3.3 实现

`AuthServiceImpl.login()` 有四个身份分支：MEMBER、IT、Admin、Owner。IT 分支完全不调用维护检查，
永远不受影响；其余三个分支（Member/Admin/Owner）在密码校验通过、`assertTenantNotExpired(...)` 之后，
`buildLoginResult(...)` 之前，各自插入一行：

```java
requireNotUnderMaintenance();
```

```java
private void requireNotUnderMaintenance() {
    if (systemMaintenanceModeService.isEnabled()) {
        throw new BusinessException(
                "System is currently under maintenance. Please try again later.",
                Map.of("maintenanceMode", true));
    }
}
```

`BusinessException` 本来就支持带一个 `Map<String, Object> payload`（`getPayload()`），
`GlobalExceptionHandler` 早就会把这个 payload 原样放进响应体的 `data` 字段——这次直接复用这个已有
机制，不需要改错误处理层、不需要新的异常类型。前端拿到 `data.data.maintenanceMode === true` 就知道
这是维护模式拦的，不是密码错，从而弹出专属提示（含 `maintenance` 表的通告内容，前端设计见前端文档）。

`updateMemberLastLogin`/`updateAdminLastLogin`/`updateOwnerLastLogin` 都排在这行检查**之后**——维护
模式拦截的登录不算真正登录成功，不应该更新 last_login 时间戳。

---

## 4. 已知缺口 / 后续

- `schema.sql` / `TABLE_MIGRATION.md` 尚未补录 `system_maintenance_mode`，计划近期一并处理。
- Realtime 秒级踢人（开关一开、已打开页面立即跳转，而不是等下一次请求）明确留给 IT 后续用纯 Spring Boot 重做的 realtime 机制，不在本次范围内。
- 维护模式登录拦截目前只验证了后端编译通过 + `BusinessException` payload 机制走通，没有做真实浏览器端到端测试（开关开启 → 已登录会话被踢 → 用正确密码重新登录看到维护弹窗）这条完整链路。

---

## 5. 参考文件

- [`migrate_add_system_maintenance_mode_table.sql`](../backend/src/main/resources/sql/migrate_add_system_maintenance_mode_table.sql)
- `SystemMaintenanceModeDao.java` / `SystemMaintenanceModeService.java` / `SystemMaintenanceModeServiceImpl.java` / `SystemMaintenanceModeController.java`（`backend/src/main/java/com/eazycount/`）
- `JwtAuthTokenFilter.java`（`backend/src/main/java/com/eazycount/jwt/`）
- `SessionUser.java` / `AuthServiceImpl.java`（`backend/src/main/java/com/eazycount/security/` / `service/impl/`）
- `BusinessException.java` / `GlobalExceptionHandler.java`（`backend/src/main/java/com/eazycount/common/` / `handler/`）
- 前端设计文档：`Count-frontend/docs/it-role-system-maintenance-mode.md`
