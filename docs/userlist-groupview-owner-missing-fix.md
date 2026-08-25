# Admin 用户列表：新建 Group 后单 Group 视图看不到自己数据

## 问题现象

- Owner 新建一个 Domain/Group（例如 Group "Q"，无下属 Company）。
- 切到 Admin 用户列表页，选择 **Company** 维度筛选（例如 Q1）能立刻看到自己 Owner 的账号数据。
- 但切到**单 Group 维度**（选中 Group "Q"，Company 栏为空）时，列表却是空的，要等很久或反复刷新页面好几次才会出现。
- 后端没有任何异步复制、消息队列或缓存延迟——建 Group 是同步事务，查询接口也不带缓存，问题完全出在前端。详见 [Count-frontend/docs/userlist-groupview-owner-missing-fix.md](../../Count-frontend/docs/userlist-groupview-owner-missing-fix.md) 的根因分析。

## 本次后端改动

为配合前端修复，新增一个**始终查库、不带任何缓存**的按 code 查 tenant id 接口，供前端在本地缓存过期/未刷新时兜底调用。

### 新增接口

`GET /auth/tenant-by-code?code=Q`

- [`AuthController.java`](../backend/src/main/java/com/eazycount/controller/AuthController.java) — 新增 `tenantByCode` 方法，鉴权方式与既有的 `/auth/tenant-accessible` 一致（`SecurityUtils.currentUser()`，未登录返回 401）。
- [`AuthService.java`](../backend/src/main/java/com/eazycount/service/AuthService.java) / [`AuthServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/AuthServiceImpl.java) — 新增 `tenantByCode(String code)`：

```java
@Override
public Map<String, Object> tenantByCode(String code) {
    SessionUser user = SecurityUtils.currentUser();
    ...
    String userType = String.valueOf(user.user_type).trim().toLowerCase();
    List<TenantDTO> rows = findAllTenantsByUserType(userType, user.user_id);
    Tenant match = TenantDtoHelper.distinctTenants(rows).stream()
            .filter(t -> t != null && normalized.equalsIgnoreCase(t.getCode()))
            .findFirst()
            .orElse(null);
    ...
}
```

复用了 `accessibleTenants()` 同一套数据源 `findAllTenantsByUserType`（owner/member/user 三种身份都走同一鉴权规则），只是把结果按 code 过滤到一条，而不是返回全量列表——语义上等价于"从 `/auth/tenant-accessible` 里挑一条"，但不用前端每次都拉全量再本地匹配。

返回格式：

```json
{ "success": true, "message": "", "data": { "tenant_id": 123, "tenant_code": "Q", "tenant_type": "GROUP" } }
```

查不到时 `data` 为 `null`（不算错误，前端按"暂不存在"处理）。

## 为什么不直接复用 `DomainDao.findTenantByCodeAndOwnerId`

`DomainServiceImpl` 内部建 Group/Company 时用的 `domainDao.findTenantByCodeAndOwnerId(code, ownerId)` 需要显式传入 `ownerId`，这对 Owner 类型的会话没问题（`user_id` 就是 `ownerId`），但对 Member/Admin 类型的会话不成立（他们的可见 tenant 集合是通过 `findTenantFeaturesByMemberId` / `findTenantFeaturesByAdminId` 算出来的，不是简单的 `owner_id` 匹配）。所以新接口选择复用 `accessibleTenants()` 同一条鉴权路径，保证跟这三种登录身份的现有权限模型完全一致，不会因为身份不同而查漏或越权。

## 验证

- `./mvnw -q -o compile` 通过，无编译错误。
- 未修改任何既有接口的行为，`domain/*`、`auth/tenant-accessible`、`auth/current-user` 等均未改动。

## 影响范围

- 新增文件：无（只在既有 `AuthController` / `AuthService` / `AuthServiceImpl` 里追加方法）。
- 纯新增只读接口，不涉及写操作，不影响现有登录/鉴权流程。
