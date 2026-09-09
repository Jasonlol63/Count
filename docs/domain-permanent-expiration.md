# Domain page — "No Expiry" permanent tenant + NO SET Confirm guard

> **最后更新**：2026-09-09

## 背景

Domain 页面（Edit Domain / Owner Confirm）原本只要 Owner 名下任一 Group/Company 的
`expiration_date` 是 NULL（"NO SET"），前端就禁用 Confirm 按钮——目的是逼员工给新客户设置到期日，
别忘了配置。但 `AP(1)`、`IG(5)`、`C168` 这类内部允许"永久免期、自由进出"的集团/公司天然就是
`expiration_date IS NULL`，导致连改 Owner 密码/邮箱都被一起卡死。

讨论后确定的方案：新增一个由 Admin 及以上角色主动选择的 "No Expiry" period，写入一个**约定的哨兵
日期**而不是 NULL，这样在数据层面就能区分「主动选择永久」和「还没配置」两种状态，同时不需要改表结构。
完整讨论过程见会话记录；本文件只记录最终落地的改动。

## 方案要点

- **不新增字段、不新增表**：复用既有的 `tenant.expiration_date`（`DATE`，可空）。
- **哨兵值**：`LocalDate.of(9999, 12, 31)`，定义为 `Tenant.PERMANENT_EXPIRATION_DATE`。序列化为 JSON
  字符串 `"9999-12-31"`，前端常量必须保持一致（见 `Count-frontend/docs/domain-permanent-expiration.md`）。
- **不进入 `renewal_period` 字典表**：这张表被 AutoRenew 客户自助续费 + Domain Fee Price 定价共用
  （`schema.sql` 里 `domain_list_fee_price` 靠 `CROSS JOIN renewal_period` 自动生成定价行）。"No
  Expiry" 不是一个可购买的续费周期，插进去会在客户续费下拉和收费设置表里冒出一行没有意义的选项。所以
  它只是 Group/Company Settings 弹窗里一个前端硬编码的特殊 Period，Save 时直接把哨兵日期当作
  `expirationDate` 传给已有的 `PUT /api/domain/update-setting` 接口，不新增后端 API、不改
  `RenewalPeriod`/`domain_list_fee_price` 相关代码。
- **`assertTenantNotExpired`（登录到期校验）不用改**：哨兵日期天然满足"未过期"，`isBefore(today)`
  永远为 false。
- **AutoRenew 自助续费流程（`AutoRenewServiceImpl.addPeriod`）暂不特殊处理**：理论上永久 tenant 不会
  提交续费单，先不加防御性代码，等真的出现这种边界情况再补。

## 改动 1 — 哨兵常量

[`backend/src/main/java/com/eazycount/entity/Tenant.java`](../backend/src/main/java/com/eazycount/entity/Tenant.java)

新增 `public static final LocalDate PERMANENT_EXPIRATION_DATE = LocalDate.of(9999, 12, 31);`，
注释说明它和 `null`（NO SET，仍然拦截 Confirm）的语义区别。

## 改动 2 — 设置"永久"需要 Admin 及以上角色（defense in depth）

[`backend/src/main/java/com/eazycount/util/AccessControlUtils.java`](../backend/src/main/java/com/eazycount/util/AccessControlUtils.java)

- 新增 `PERMANENT_EXPIRATION_ROLES = Set.of("OWNER", "PARTNERSHIP", "ADMIN")`（对齐
  `docs/admin-permission-rbac-hierarchy.md` 的角色层级，Partnership 也算 Admin 及以上）。
- 新增 `assertCanSetPermanentExpiration(SessionUser session)`：角色不在白名单内直接
  `throw new BusinessException("No permission to set No Expiry Date")`。

[`backend/src/main/java/com/eazycount/service/impl/DomainServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/DomainServiceImpl.java)
`updateTenantDetailsSetting`（Group/Company Settings 弹窗 Save 对应的 `PUT
/api/domain/update-setting`）：保存前先判断 `tenant.getExpirationDate()` 是否等于
`PERMANENT_EXPIRATION_DATE`，是的话调用上面的角色校验，避免有人绕过前端的下拉框隐藏逻辑直接打接口。

前端只对 Admin 及以上角色渲染这个 Period 选项，这里是后端兜底，两边都要过才能真正写入哨兵值。

## 改动 3（已回退）— NO SET 拦截 Confirm 的后端强制校验

一度在 `updateDomain`（`PUT /api/domain/update`，Edit Domain 弹窗 Confirm）里加过一段后端校验：保存
Owner 前先查这个 Owner 名下所有已关联的 Group/Company，只要有一个 `expirationDate == null` 就
`throw new BusinessException`，作为前端"NO SET 不能点 Confirm"规则的 defense-in-depth。

**这段校验有 bug，已经整段回退、移除。** 问题是它查的是 `domainDao.findAllTenantsByOwner(ownerId)`
——**数据库里已经持久化的状态**，而不是这次 Confirm 请求里 `domainDTO.getGroups()`/`getCompanies()`
**即将保存的值**。Group/Company Settings 弹窗选 "No Expiry" 之后，`expirationDate` 只是先暂存在前端
`tempGroups`/`tempCompanies` 的本地 state 里，真正落库要等整个 Edit Domain 表单一起 Confirm、走
`syncAllTenantSettings` 才会调用 `PUT /api/domain/update-setting` 写进 DB。于是这段校验永远读到"改之
前"的旧值：即使用户已经在弹窗里把 AP/IG 设成了 "No Expiry"，DB 里当时还是 `NULL`，Confirm 时就被这段
校验误伤拦下，报 `Cannot save: "AP" has no Expiry Date set (NO SET)`。

现在恢复成最初的样子：`updateDomain` 对 Owner 密码/邮箱更新完全不做 `expiration_date` 相关校验，
NO SET 拦截 Confirm 依旧只是纯前端行为（`DomainFormModal.jsx` 的 `findMissingExpirationDate` /
`confirmBlockedByExpiration`，见 `Count-frontend/docs/domain-permanent-expiration.md`）。如果后续还想
补一层后端强制，需要改成校验**请求体里的** `domainDTO.getGroups()`/`getCompanies()`，而不是查 DB。

## 未做的部分

- **操作日志**：谁在什么时候把哪个 Group/Company 设为永久，目前没有记录。用户已确认这个留到后续的
  "操作日志"大功能里一起做，不阻塞本次改动。
- **AutoRenew 续费单防御**：如果一个已经是永久状态的 tenant 意外提交了续费请求，`addPeriod()` 目前
  不会拒绝，只是理论上不会发生（永久 tenant 不需要续费，客户端也不会引导他们这么做）。

## 影响文件
- `backend/src/main/java/com/eazycount/entity/Tenant.java`
- `backend/src/main/java/com/eazycount/util/AccessControlUtils.java`
- `backend/src/main/java/com/eazycount/service/impl/DomainServiceImpl.java`

## 前端
`Count-frontend` 侧的 Period 选项、展示文案（Group/Company Settings 弹窗 + Selected Groups/Companies
列表 + Sidebar 到期区块）见
[`Count-frontend/docs/domain-permanent-expiration.md`](../../Count-frontend/docs/domain-permanent-expiration.md)。
