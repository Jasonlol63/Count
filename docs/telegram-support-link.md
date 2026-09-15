# 登录页 Telegram 客服链接 — 全局单例配置

> **范围**：新表 `platform_settings`（单行/单例），`PlatformSetting` entity，`PlatformSettingDao` +
> `PlatformSettingLink.xml`，`PlatformSettingService`/`Impl`，`PlatformSettingController`，
> `SecurityConfig` 加一条公开路径。前端对应文档：`Count-frontend/docs/telegram-support-link.md`。
> **最后更新**：2026-09-15

---

## 1. 需求 & 设计决策

登录页右下角要有一个 Telegram 客服悬浮按钮，链接由管理员在后台维护，**全局唯一一条**（不分
Company/Group，因为登录页本身就是按 `tenant` 区分的，客服团队是平台统一的）。

### 1.1 为什么是新建一张 DB 表，不是 Redis、也不是塞进现有表

过程中依次排除了几个"更省事"的方案，逐条记录原因：

- **Redis（一开始的方案）**：项目里 `AuthTokenStore`/`PasswordResetTacStore` 已经在用
  `StringRedisTemplate`，一开始想直接存一个 key，省掉建表。后来查证：
  - 这个项目里 Redis 现有用法**全部带 TTL**（登录 token、验证码），本质是缓存/会话数据，没有"永久
    保留"的先例
  - `scripts/start-redis.ps1` 只是裸启动 `redis-server --port 6379 --daemonize yes`，没开
    AOF、没有明确 save 策略，仓库里也没有 docker-compose / systemd 之类的部署配置能确认生产环境
    有没有持久化
  - 结论：业务配置（管理员手动设置、期望永久生效）放在一个"从没被当作要保留数据来配置"的 Redis
    实例里，重启/部署时有静默丢失风险。**改回 DB。**
- **塞进现有表**（比如 `tenant` 表的 C168 那一行，或 `announcements`/`maintenance_marquee`）：
  - `tenant` 表没有任何 JSON/extra 之类可扩展字段，而且这是**全局配置**，不该跟着某一个租户的行走
    （万一那行数据被删/迁移，配置也跟着没了；语义上也是"平台配置"混进"租户配置"）
  - `announcements`/`maintenance_marquee` 是内容列表表（CRUD + 审计字段），不是配置存储，语义不符
  - 查 `schema.sql` 发现这个项目历史上是**主动把 JSON 配置字段拆成正经字段/正经表**的（
    `tenant_fee_share_allocation`、`account_tenant_access` 等表的注释都写着"replaces ... JSON"），
    说明"塞一个通用 JSON 配置列"也不是这个项目认可的做法
  - 结论：**新建一张专用的单行表最符合这个项目自己的架构风格**，不是过度设计

---

## 2. 数据库

新表 `platform_settings`，单例模式：永远只有 `id = 1` 这一行，由迁移脚本
[`migrate_add_platform_settings_table.sql`](../backend/src/main/resources/sql/migrate_add_platform_settings_table.sql)
用 `INSERT IGNORE` 播种。

```sql
CREATE TABLE IF NOT EXISTS `platform_settings` (
    `id`                    TINYINT UNSIGNED NOT NULL COMMENT 'Always 1 -- singleton row',
    `telegram_support_link` VARCHAR(500) NULL,
    `updated_by`            VARCHAR(50)  NULL,
    `updated_by_type`       ENUM('USER', 'OWNER') NULL,
    `updated_at`            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
INSERT IGNORE INTO `platform_settings` (`id`) VALUES (1);
```

没有 `company_code`/`tenant_id`——故意不挂靠任何租户。留了列扩展空间：以后如果还有别的全局开关/
链接，直接在这张表加列即可，不用再开新表（但不做成 JSON 列，理由见 §1.1）。同步更新了
`schema.sql`。

---

## 3. 后端分层

| 文件 | 职责 |
|------|------|
| [`PlatformSetting.java`](../backend/src/main/java/com/eazycount/entity/PlatformSetting.java) | 实体，`id/telegramSupportLink/updatedBy/updatedByType/updatedAt` |
| [`PlatformSettingDao.java`](../backend/src/main/java/com/eazycount/dao/PlatformSettingDao.java) | `findLink()`（无参）、`updateLink(PlatformSetting)` |
| [`PlatformSettingLink.xml`](../backend/src/main/resources/mybatis/PlatformSettingLink.xml) | MyBatis mapper |
| [`PlatformSettingService.java`](../backend/src/main/java/com/eazycount/service/PlatformSettingService.java) / [`Impl`](../backend/src/main/java/com/eazycount/service/impl/PlatformSettingServiceImpl.java) | `getLink()` 无鉴权（公开读）；`updateLink()` 走 `AccessControlUtils.requireWritable`，校验链接格式 |
| [`PlatformSettingController.java`](../backend/src/main/java/com/eazycount/controller/PlatformSettingController.java) | `GET /api/settings/getTelegramLink`（公开）、`POST /api/settings/updateTelegramLink`（需登录+可写） |
| `SecurityConfig.java` | `PUBLIC_URLS` 加了 `/api/settings/getTelegramLink` |

鉴权力度跟 `AnnouncementServiceImpl` 保持一致——**没有**额外的"仅超级管理员"角色门槛，登录 + 非只读
账号（`AccessControlUtils.requireWritable`）即可保存，这是项目里 Announcement/Maintenance 写接口
统一采用的模式。

`getTelegramLink` 的响应同时带上 `updatedBy`/`updatedAt`（不只是链接本身），给前端 Contact 面板展示
"UPDATED BY / UPDATED AT" 用。

---

## 4. 过程中修过的 bug

这个功能是分几轮做的：迁移 SQL 先落地，用户自己写了第一版 entity/DAO/mapper/service，之后由我 review
并修复，再补 controller。记录几个修过的坑，避免以后重蹈：

1. **Mapper namespace 写错**：`PlatformSettingLink.xml` 一开始 `namespace="com.eazycount.dao.PermissionDao"`
   （复制别的文件忘记改），导致 MyBatis 找不到对应 statement，启动报错。
2. **DAO 方法名跟 mapper statement id 对不上**：DAO 声明 `insert/update/delete`，mapper 里是
   `AddNewLink/updateLink/deleteLink`，MyBatis 靠方法名绑定，全部对不上。
3. **`insert()` 把方法参数重新赋值成一个空对象**（`platformSetting = new PlatformSetting();` 之后
   再从这个空对象读值存回去），导致不管管理员填什么链接，最终存进 DB 的都是 NULL。
4. **`findAllLink()`（读接口）强制要求登录**——但这个查询恰恰是要给未登录的登录页用的，这个逻辑一旦
   保留，整个功能就是死的。
5. **`update()` 是空方法**，没有真正调用 DAO，保存功能没实现。
6. **不该有 `insert`/`delete`**：这是迁移时就播种好的单例表，业务上只需要"改"，`insert` 用的
   `useGeneratedKeys="true"` 在非自增主键上本来就会失败，`delete` 会把唯一一行删掉、搞垮公开读接口。

最终精简成只有 `findLink()` + `updateLink()` 两个操作，`findLink()` 完全不做鉴权，`updateLink()`
统一走 `AccessControlUtils.requireWritable` + 链接格式校验。

### 4.1 2026-09-15 回归：mapper 改成 `WHERE id = #{id}` 后没人设置 id

后来 `PlatformSettingLink.xml` 的 `updateLink` 语句从写死的 `WHERE id = 1` 改成了参数化的
`WHERE id = #{id}`，但 `PlatformSettingServiceImpl.updateLink()` 从来没有给传入的 `PlatformSetting`
设置过 `id`——它来自 `@RequestBody`，前端只会发 `{telegramSupportLink}`，`id` 反序列化后是
`int` 默认值 `0`。结果是 `UPDATE ... WHERE id = 0` 匹配不到任何行，**保存请求返回成功但实际什么都
没存进去**，且 MyBatis 对 0 行受影响的 UPDATE 默认不报错，不会被轻易发现。

修法：在 `updateLink()` 里显式 `platformSetting.setId(1)`（单例表的不变量放在 service 层，一行
注释说明原因），而不是把 mapper 改回写死的 `WHERE id = 1`——保留了 mapper 参数化的写法，把"这张表
永远只有 id=1"这个业务规则放在更合适的 service 层。

---

## 5. 已知缺口

- 未做端到端真实登录测试（需要本地 MySQL + Redis + Spring Boot 都在跑，用真实管理员账号走一遍
  "Contact 分页填链接 → 保存 → 登录页刷新看到按钮"）。
- 链接格式校验目前只是简单正则（`^https?://\S+$`），不校验是不是真的 `t.me` 域名——前端虽然固定了
  `https://t.me/` 前缀，但后端本身不强制这一点，直接调 API 传别的域名也能存进去。目前认为可以接受
  （管理员是可信角色），如果以后要收紧可以在 service 层加域名白名单校验。

---

## 6. 参考文件

- [`migrate_add_platform_settings_table.sql`](../backend/src/main/resources/sql/migrate_add_platform_settings_table.sql)
- [`PlatformSetting.java`](../backend/src/main/java/com/eazycount/entity/PlatformSetting.java)
- [`PlatformSettingDao.java`](../backend/src/main/java/com/eazycount/dao/PlatformSettingDao.java) /
  [`PlatformSettingLink.xml`](../backend/src/main/resources/mybatis/PlatformSettingLink.xml)
- [`PlatformSettingService.java`](../backend/src/main/java/com/eazycount/service/PlatformSettingService.java) /
  [`PlatformSettingServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/PlatformSettingServiceImpl.java)
- [`PlatformSettingController.java`](../backend/src/main/java/com/eazycount/controller/PlatformSettingController.java)
- 前端对应文档：`Count-frontend/docs/telegram-support-link.md`
