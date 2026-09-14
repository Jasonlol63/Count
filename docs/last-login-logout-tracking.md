# Admin / Owner / Account：Last Login & Last Logout 追踪

后端在三张身份表（`user` = Admin-tab、`owner` = Owner、`account` = Member-tab）上补齐了登录/登出
时间戳，供 Admin User List 和 Account List 的 "Last Login" / "Last Logout" 两列展示使用。

前端展示/交互细节见 `Count-frontend` 仓库的
[`docs/last-login-logout-ui.md`](../../Count-frontend/docs/last-login-logout-ui.md)。这份文档只
记录后端部分。

## 覆盖范围的共同限制

三张表的 `last_logout` **只在成功调用 `POST /auth/logout` 时才会写入**。浏览器直接关闭标签页、
token 自然过期而没有走这个接口的情况，不会被记录——这是当前认证机制（JWT + Cookie，前端主动登出）
下的固有限制，不是 bug。

## 1. Admin（`user` 表）

`last_login` 本来就存在且已经在写入，只是 `last_logout` 和更新逻辑是新加的。

- **Schema**：[`schema.sql`](../backend/src/main/resources/sql/schema.sql) 加 `last_logout DATETIME
  DEFAULT NULL`；已有库用
  [`migrate_add_last_logout_to_user.sql`](../backend/src/main/resources/sql/migrate_add_last_logout_to_user.sql)
  补齐（`information_schema` 判断后 `ADD COLUMN`，可安全重跑）。
- **实体**：[`Admin.java`](../backend/src/main/java/com/eazycount/entity/Admin.java) 加
  `lastLogout` 字段。
- **写入**：
  [`AuthDao.updateAdminLastLogout`](../backend/src/main/java/com/eazycount/dao/AuthDao.java) +
  [`LoginMapper.xml`](../backend/src/main/resources/mybatis/LoginMapper.xml) 的
  `UPDATE user SET last_logout = NOW() WHERE id = #{adminId}`。
  [`AuthServiceImpl.logout()`](../backend/src/main/java/com/eazycount/service/impl/AuthServiceImpl.java)
  按 `SessionUser.user_type == "user"` 分流调用。
- **读取**：[`AdminMapper.xml`](../backend/src/main/resources/mybatis/AdminMapper.xml) 的
  `AdminListDTO` resultMap 及 `findAdminsByTenantId` / `findDuplicateLoginIdLoginId` /
  `findAdminByUserIdAndTenantId` / `findAdminById` 都加了 `last_logout` 列映射。
- **顺手没动的已知问题**：Owner 通过 Admin-tab 登录（`AuthServiceImpl.login()` 里
  `findAdminByLoginId` 落空后才 fallback 到 `findOwnerByOwnerCode` 的那条分支，见下一节）已经会
  更新 `owner.last_login`；但如果未来发现 `user` 表本身在某条登录分支漏更新 `last_login`，那是独立
  问题，本次没有改动这部分。

## 2. Owner（`owner` 表）

`owner` 表之前**完全没有**任何登录/登出时间字段，即使 Admin User List 一直有个 Owner 的"影子行"
展示 Last Login 列，数据源头也从来不存在，所以永远显示占位符。

- **Schema**：[`schema.sql`](../backend/src/main/resources/sql/schema.sql) 加 `last_login` +
  `last_logout` 两列（都是新的）；已有库用
  [`migrate_add_last_login_logout_to_owner.sql`](../backend/src/main/resources/sql/migrate_add_last_login_logout_to_owner.sql)。
- **实体**：[`Owner.java`](../backend/src/main/java/com/eazycount/entity/Owner.java) 加
  `lastLogin` / `lastLogout` 字段。
- **写入**：`AuthDao.updateOwnerLastLogin` / `updateOwnerLastLogout` +
  `LoginMapper.xml` 对应 SQL。`AuthServiceImpl.login()` 的 Owner 分支（`findOwnerByOwnerCode`
  验证通过后）调用 `updateOwnerLastLogin`；`logout()` 按 `user_type == "owner"` 调用
  `updateOwnerLastLogout`。
- **读取**：[`DomainMapper.xml`](../backend/src/main/resources/mybatis/DomainMapper.xml) 的
  `OwnerMap`（`findOwnerById` 用 `select *`，之前完全没映射这两列，读出来恒为 `null`）补上
  `lastLogin` → `last_login`、`lastLogout` → `last_logout`。
- **传到 Admin User List 的影子行**：
  [`AdminServiceImpl.mapOwnerToAdminShell()`](../backend/src/main/java/com/eazycount/service/impl/AdminServiceImpl.java)
  把 `owner.getLastLogin()` / `owner.getLastLogout()` 塞进合成的 `Admin` 对象，序列化后跟 Admin
  行走的是同一个 `admin.lastLogin` / `admin.lastLogout` JSON 字段，前端不需要区分 Admin 行还是
  Owner 影子行。

## 3. Account / Member（`account` 表）

`last_login` 本来就存在且登录时已经在写入（`AuthServiceImpl.login()` 的 `LoginRole.MEMBER`
分支），但列表查询从来没有把它 SELECT 出来，所以前端一直显示占位符；`last_logout` 是全新的。

- **Schema**：[`schema.sql`](../backend/src/main/resources/sql/schema.sql) 加 `last_logout
  DATETIME DEFAULT NULL`；已有库用
  [`migrate_add_last_logout_to_account.sql`](../backend/src/main/resources/sql/migrate_add_last_logout_to_account.sql)。
- **实体**：[`User.java`](../backend/src/main/java/com/eazycount/entity/User.java)（对应
  `account` 表，命名容易和 Admin/Owner 的 "User" 混淆，注意区分）加 `lastLogout` 字段。
- **写入**：`AuthDao.updateMemberLastLogout` + `LoginMapper.xml` 对应 SQL；
  `AuthServiceImpl.logout()` 按 `user_type == "member"` 分流调用。
- **DTO**：[`UserListDTO.java`](../backend/src/main/java/com/eazycount/dto/UserListDTO.java) 加
  `lastLogout` 字段（`lastLogin` 本来就有）。
- **读取 bug 修复**：[`AccountMapper.xml`](../backend/src/main/resources/mybatis/AccountMapper.xml)
  的 `findUserByTenantId` / `findUserByIdAndTenantId` 两处 SELECT 之前把列名写成驼峰
  `a.lastLogin` / `a.lastLogout`，但实际数据库列是下划线 `last_login` / `last_logout`——这两个
  接口一跑就会报 `Unknown column`。已改成 `a.last_login AS accountLogin` /
  `a.last_logout AS accountLogout`，跟 `UserListDTOMap` 的既有映射对上。

## 涉及文件汇总

- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/resources/sql/migrate_add_last_logout_to_user.sql`（新增）
- `backend/src/main/resources/sql/migrate_add_last_login_logout_to_owner.sql`（新增）
- `backend/src/main/resources/sql/migrate_add_last_logout_to_account.sql`（新增）
- `backend/src/main/java/com/eazycount/entity/Admin.java`
- `backend/src/main/java/com/eazycount/entity/Owner.java`
- `backend/src/main/java/com/eazycount/entity/User.java`
- `backend/src/main/java/com/eazycount/dto/UserListDTO.java`
- `backend/src/main/java/com/eazycount/dao/AuthDao.java`
- `backend/src/main/resources/mybatis/LoginMapper.xml`
- `backend/src/main/resources/mybatis/AdminMapper.xml`
- `backend/src/main/resources/mybatis/DomainMapper.xml`
- `backend/src/main/resources/mybatis/AccountMapper.xml`
- `backend/src/main/java/com/eazycount/service/impl/AuthServiceImpl.java`
- `backend/src/main/java/com/eazycount/service/impl/AdminServiceImpl.java`

## 已知限制

- 只在本地开发库验证过 `mvn compile` 和迁移脚本语法；正式库（`count_real`）还没跑过这三条
  migration。
- 没有端到端自动化测试，联调时需要人工验证三种登录路径（Admin/Owner/Member）各自的
  Login/Logout 时间是否正确落库、以及在对应列表页面正确显示。
