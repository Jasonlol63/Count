# Admin 账号级侧边栏权限覆盖（Permission Override）（2026-08-27）

> 配套/前置文档：`docs/admin-permission-rbac-hierarchy.md`（角色层级 + read_only 全局校验）——本文档是在那之上加的另一个独立功能，两者互不依赖。

## 背景

之前 Edit User 弹窗里的 "Choose permissions" 复选框一直是纯 UI 状态：前端会把勾选结果放进 `permissions: string[]` 字段发给 `/api/userlist/add`/`update`，但 `AdminServiceImpl` 从头到尾没有任何地方读取这个字段——侧边栏权限完全、只按角色（`user_role_permission`）决定，账号级别的勾选提交了也是被静默丢弃。

这次要支持：**在角色默认权限之外，单独给某个账号加/减入口，不影响同角色的其他账号**。

## 设计

没有采用"角色默认 ∪ 账号级 ALLOW/DENY 差集"这种更"通用"的模型（讨论过程见对话记录），原因：
- 差集模型每次都要读两份数据再合并，读取路径变长；
- 跟仓库里 `account_acl_mode`/`process_acl_mode`（`AdminTenantAccess.AclMode`：ALL/CUSTOM/NONE）已经确立的"一个账号非此即彼"的设计语言不一致，多引入一套合并逻辑对维护没有好处；
- 需求本身只是"角色默认"或"这个账号自己的完整清单"二选一，不需要更复杂的模型。

最终采用跟 `AclMode` 同源的二选一设计：

```
user.permission_mode = ROLE_DEFAULT（默认）→ 侧边栏权限 100% 来自 user_role_permission，零额外查询
user.permission_mode = CUSTOM         → 侧边栏权限 100% 来自 user_permission_override 这个账号自己的完整清单
                                          （可以比角色默认多，也可以比角色默认少，两者永远不合并）
```

## 数据库

- [`migrate_add_user_permission_override.sql`](../backend/src/main/resources/sql/migrate_add_user_permission_override.sql)（幂等，跑在已有库上）+ `schema.sql` 基线同步：
  - `user` 表加 `permission_mode ENUM('ROLE_DEFAULT','CUSTOM') NOT NULL DEFAULT 'ROLE_DEFAULT'`
  - 新增 `user_permission_override(user_id, permission_id)`，`user_id` FK 级联删除，按账号精确隔离

## 后端改动

- **`entity/Admin.java`**：加 `permissionMode` 字段 + `PermissionMode { ROLE_DEFAULT, CUSTOM }` 枚举（MyBatis 默认按枚举名字符串映射，跟 `AclMode` 用法一致，没加额外配置）。
- **新增 `entity/UserPermissionOverride.java`**：`{ userId, permissionId }`，仿照 `AdminTenantAccountAccess` 的简单行实体写法。
- **`dao/PermissionDao.java`** 加 `findOverridePermissionsByUserId`；**`dao/AdminDao.java`** 加 `insertOverridePermissionsBatch`、`deleteOverridePermissionsByUserId`。
- **Mapper XML**：
  - `PermissionMapper.xml` 新增对应 select（照抄 `findActivePermissionsByRoleId` 的写法）。
  - `AdminMapper.xml`：`insertAdmin`/`updateAdmin` 带上 `permission_mode` 列；`findAdminById` 的 SELECT 加了这一列；新增 override 表的 insert/delete。
  - `LoginMapper.xml`：`AdminMap`/`AdminColumns` 加 `permission_mode`——这是**登录时**真正影响 session 菜单的地方。
- **`PermissionServiceImpl`**：把原来 `resolveModuleKeysForRoleId` 里"C168 extras + feature gate 过滤 + 排序"这段公共逻辑拆成 `resolveModuleKeysFromPermissions`，角色默认路径和 CUSTOM 路径共用；`resolveAdminModuleKeys` 按 `admin.getPermissionMode()` 二选一读取，永远只读一个来源，不合并。
- **`AdminServiceImpl`**：
  - 新增 `resolvePermissionMode(submittedPermissions, roleId)` — 提交的清单如果跟角色默认完全一致（或没传）就是 `ROLE_DEFAULT`，否则 `CUSTOM`（永远是完整清单，不是差集）。
  - 新增 `persistPermissionOverrides(userId, mode, submittedPermissions)` — 先删后插，跟现有 `replaceAccountAcl`/`replaceProcessAcl` 一个套路，天然幂等；`ROLE_DEFAULT` 时只做清空。
  - 新增 `resolveEffectiveSidebarPermissionCodes(admin)` — 给编辑详情用，CUSTOM 读 override 表，否则退回角色默认。
  - `persistUserForCreate`/`persistUserForUpdate`：角色解析完之后算 `permissionMode` 并写入 `admin` 对象（跟着 insert/update 一起落库），写库成功后调用 `persistPermissionOverrides`。
  - `getAdminDetailByUserId`：把回显权限列表的调用换成 `resolveEffectiveSidebarPermissionCodes(admin)`。

## 踩坑记录：一个自己漏改的 bug（已修复）

`getAdminDetailByUserId` 内部其实有两处拼 `detail.setPermissions(...)` 的地方：

1. Owner 影子行分支（`ownerShadow == true`，很少走到）
2. **普通账号分支（`scopedAccess != null`，几乎所有真实账号，包括测试用的 Customer Service / Audit 账号都走这条）**

第一版改动用 `replace_all` 只成功换掉了第 1 处，第 2 处（真正被使用的那条）还是旧的 `resolveSidebarPermissionCodes(admin.getRoleId())`——纯读角色默认，完全不看 override 表。

**症状**：登录后侧边栏能正确看到额外权限（`PermissionServiceImpl` 那边改对了，是独立的另一套代码路径），但重新打开 Edit User 详情弹窗，权限列表永远只显示角色默认，看起来像是"保存了又被清空"。

**排查过程**：一开始怀疑是前端 `computeRowCapabilities`（层级 gate 导致 `permissions` 字段没发）、`ROLE_HIERARCHY` 里 `"customer service"`（空格）vs 角色码 `CUSTOMER_SERVICE` 经 `normRole()` 转出来的 `"customer_service"`（下划线）不匹配（这个 key 不一致确实是个真实存在的独立小 bug，但数学上推导过，不是这次症状的成因）、数据库迁移没跑、后端没重启——一个个排除之后，最后翻回后端代码逐行核对才发现是这个漏改的调用点。用户提供的"登录后侧边栏正常，但编辑详情页看不到"这个关键区别信息，是定位到问题的决定性线索。

现在两处都指向 `resolveEffectiveSidebarPermissionCodes`。

## 前端

**不需要改动。** 已确认现有的 `permSelected`/`permDisabledMap`/save 逻辑本来就会把完整勾选清单发给后端（`permissions` 字段），只是后端之前完全不处理。

## 验证方式

1. 编辑一个非 Owner/Partnership/Admin 角色的账号（比如 Customer Service 或 Audit），在 Permissions 里额外勾选角色默认之外的入口，保存。
2. 重新打开同一账号的 Edit User，确认权限列表包含角色默认 + 新加的入口（这一步是本次修的 bug，之前会丢失）。
3. 用该账号登录，确认侧边栏也出现了额外入口（这一步之前就是对的）。
4. 检查同角色的**其他**账号权限没有被影响（按 `user_id` 精确隔离）。
