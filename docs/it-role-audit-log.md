# IT 角色 · 操作日志控制台

> **范围**：新增 IT 账号体系（独立于 admin/user 表）+ 全站 CRUD 审计日志。
> **状态**：IT 登录分支已实现并验证；`audit_log` 表已建；采集/查询/Restore 后端代码未开始。
> **前端对应**：`Count-frontend/src/pages/auditlog/`（`AuditLogPage.jsx` 等，概念设计已落地为真实组件，接的是 `GET /api/it/audit-log` 等尚未实现的接口，暂时 fallback 到内置示例数据）。
> **最后更新**：2026-09-15

---

## 1. 需求 & 设计决策

IT 需要能踢人、查看跨公司的操作日志、对误删数据做 Restore，且这套能力要**跟 Admin 现有的角色/权限体系完全隔离**（不出现在 Admin 的角色下拉里，不影响现有 hierarchy 判断）。本文档记录已经做了 IT 账号 + 审计日志这两块的设计决策；踢人/跨公司自由访问留到后面阶段。

### 1.1 IT 账号为什么不进 user/admin 表

- 讨论过硬编码进 Java 源码——排除：密码明文进代码/部署产物，换密码要重新编译发版，且多个 IT 人员共用不利于审计归属。
- 最终方案：**外部 yml 文件**（`it-operators.yml`，项目根目录，`.gitignore` 排除，不进 git）+ `ItOperatorRegistry`（启动时读进内存）。密码用跟 Admin/Owner 同一个 `BCryptPasswordEncoder`（`SecurityConfig#passwordEncoder`）。
- 登录复用现有的 "admin" 登录 tab（`LoginRole.ADMIN`），`AuthServiceImpl.login()` 里在查 Admin 表**之前**先查这个 registry，命中就走独立分支，不触碰 Admin/Owner 表；`SessionUser.from(UserDTO...)` 分发逻辑加了 `fromItOperator(...)`，不复用 `fromAdmin` 那套（因为需要 `PermissionService` 查真实 DB 权限，IT 没有对应的行）。
- 现阶段（跨公司功能未做）IT 登录依然要填一个真实存在的 `tenant_code`，正常按公司登录，没有特殊校验；`buildLoginResult` 给 IT 一个专属 `userType="it"`、`redirect="/audit-log"`（跳过 secondary password，不落地到 dashboard——因为"无限制跨公司浏览 dashboard"是后面阶段的工作）。

### 1.2 审计日志表结构为什么长这样

- **没有 `@WriteOperation`/AOP 切面可用**——排查过 `SecurityConfig.java` 里的注释提到这两个名字，但实际代码库里完全不存在，纯粹是文档性质的注释。这个项目至今没有引入过 AOP，写操作管控靠每个 Service 方法手写 `AccessControlUtils.requireWritable(session)`。审计日志的采集因此**照抄这个风格**：在需要记录的 Service 方法里显式调用 `auditLogService.record(...)`，不是自动生效的注解。先只接 Payment/BankProcess/CaptureTransaction 的删除，规模变大后再评估要不要升级成注解+切面。
- **`before_data`/`after_data` 用 `TEXT` 不用 MySQL 原生 `JSON` 类型**——列里存的内容还是 JSON 格式字符串，只是列类型不是 `JSON`，理由是不想引入 JSON 类型自身的存储/索引特性。
- **一条操作一行，不按字段拆行**——中途考虑过把每个变化字段拆成单独一行存（`audit_log_field` 表），可以直接按字段查询，但算过量级：一次操作涉及 N 个字段就要写 N 行，每行还有主键+外键+InnoDB 行开销的固定成本，实际存储/索引维护量比压成一个文本块**更大**，而且当前也没有"按字段搜索历史"这个真实需求，所以放弃，改回一条操作一行。展示层的"字段对比表格"效果在读取时由后端解析文本、拼成 `[{field, before, after}]` 数组给前端渲染，不需要数据库层面拆字段。
- **`source_table` 字段 + 字段名对齐数据库列名**——给 IT 在没有 Restore 能力的模块（如 ACCOUNT）提供人工补数据的依据，日志详情里能直接看出该插回哪张表、字段名不用猜。
- **`transactions_deleted` 是 Payment/BankProcess/CaptureTransaction 共用的同一张归档表**（不是三张分开的表）——所以 Restore 未来只需要一个通用实现（按 `transaction_id` 从 `transactions_deleted` 搬回 `transactions`），"搬回去"这个方向目前完全不存在，要从零写。`data_capture_line_deleted`（CaptureTransaction 删除连带归档的明细行）要不要一起恢复，还没定。

---

## 2. 数据库

新表 `audit_log`，迁移脚本
[`migrate_add_audit_log_table.sql`](../backend/src/main/resources/sql/migrate_add_audit_log_table.sql)，已同步进
[`schema.sql`](../backend/src/main/resources/sql/schema.sql)（"Global (non-tenant-scoped) tables" 一节，`platform_settings` 之后）。字段/理由见迁移脚本注释与上面 §1.2。

---

## 3. 后端分层（进度）

| 文件 | 职责 | 状态 |
|------|------|------|
| `it-operators.yml`（项目根目录，不进 git） | IT 账号配置 | ✅ |
| `ItOperatorProperties.java` / `ItOperatorRegistry.java` / `ItOperatorIdentity.java`（`com.eazycount.security`） | 绑定 yml、按用户名查找、密码校验 | ✅ |
| `UserDTO.java` / `SessionUser.java` / `AuthServiceImpl.java` | IT 登录分支、`fromItOperator` 分发 | ✅ |
| `audit_log` 表 | 见 §2 | ✅ |
| `AuditLogDao` / `AuditLogMapper.xml` / `AuditLogService` | 采集 + 查询 | 未开始 |
| `AuditLogController`（`/api/it/audit-log`...） | 对外接口，仅 `role=="it"` 可访问 | 未开始 |
| Restore（通用 `transactions_deleted` 恢复） | 见 §1.2 | 未开始，`data_capture_line_deleted` 连带恢复范围待定 |

---

## 4. 踩过的坑

1. **`it-operators.yml` 一开始放错目录**——放在 `backend/it-operators.yml`，但 `pom.xml` 的 `<sourceDirectory>` 指向 `backend/src/main/java`，说明这个项目实际的 Maven 构建/运行根目录是 `Count/`（`target/` 也生成在这一层），不是 `backend/`。`application.yml` 里 `spring.config.import: optional:file:./it-operators.yml` 是相对运行时工作目录解析的，`optional:` 前缀导致文件找不到时不报错、静默跳过，IT 注册表变成空的，登录直接判"用户名或密码错误"，很难第一时间看出是路径问题。**修法**：文件挪到 `Count/it-operators.yml`（跟 `pom.xml` 同级）。

---

## 5. 已知缺口

- 采集（`AuditLogService.record()`）、查询接口、Restore 全部未实现——见 §3 进度表。
- `it-operators.yml` 里密码即时吊销（Redis 禁用名单）还没做，属于后面"踢人"阶段的范围。
- 前端 `AuditLogPage.jsx` 目前请求真实接口失败后会 fallback 到内置示例数据（`sampleAuditLogs.js`），接口做完后要验证 fallback 正确让路给真实数据。

---

## 6. 参考文件

- [`migrate_add_audit_log_table.sql`](../backend/src/main/resources/sql/migrate_add_audit_log_table.sql)
- [`schema.sql`](../backend/src/main/resources/sql/schema.sql)（"Global" 一节）
- [`TABLE_MIGRATION.md`](../backend/src/main/resources/sql/TABLE_MIGRATION.md)（§3.8 / §5 / §6 / §7 已补录）
- `ItOperatorProperties.java` / `ItOperatorRegistry.java` / `ItOperatorIdentity.java`（`backend/src/main/java/com/eazycount/security/`）
- `AuthServiceImpl.java`（`backend/src/main/java/com/eazycount/service/impl/`）
- 前端：`Count-frontend/src/pages/auditlog/`
