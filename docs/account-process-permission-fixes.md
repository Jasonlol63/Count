# Account / Process 数据权限修复

Edit User 页面的 Account / Process 勾选，控制一个 admin/staff 登录后能在
`/api/account/list`（Account 列表页）、`/api/process/process-list`（Process 列表页）
里看到哪些数据。这份文档记录围绕这个功能修的几个 bug。

## 涉及文件

- `backend/src/main/java/com/eazycount/service/impl/UserServiceImpl.java`
- `backend/src/main/java/com/eazycount/service/impl/ProcessServiceImpl.java`
- `backend/src/main/java/com/eazycount/service/impl/AdminServiceImpl.java`
- `backend/src/main/java/com/eazycount/dto/AdminDTO.java`（无残留改动，最终与改动前一致）
- 前端未改动（曾短暂加过一个多租户标签页 UI，已完全撤销）

## 数据模型

- `AdminTenantAccess`（表 `user_tenant_access`）：每个 (admin, tenant) 一条记录，
  `accountAclMode` / `processAclMode` 取值 `ALL`（不限制）/ `NONE`（清空）/ `CUSTOM`（自定义白名单）。
- `AdminTenantAccountAccess`（表 `user_tenant_account_access`）：`CUSTOM` 模式下的账户白名单，FK 到 `account.id`。
- `AdminTenantProcessAccess`（表 `user_tenant_process_access`）：`CUSTOM` 模式下的流程白名单，FK 到 `process.id`。
- `account`、`process` 都是**严格按单一 tenant_id 归属**的表，没有跨租户共享。

## 问题 1：读取接口没有做权限过滤

**现象**：Edit User 里清空 Account/Process 勾选并保存后，该账号登录仍能看到全部数据。

**原因**：`/api/account/list`、`/api/process/process-list` 只是无条件查询整个租户下的所有数据，
完全没有读取 `AdminTenantAccess`/`AdminTenantAccountAccess`/`AdminTenantProcessAccess` 这几张权限表。

**修复**：`UserServiceImpl.findUserByTenantId` 新增 `filterByAccountAcl`，
`ProcessServiceImpl.findProcessByTenantId` 新增 `filterByProcessAcl`，逻辑对称：

```java
SessionUser session = SecurityUtils.currentUser();
if (session == null || !"user".equalsIgnoreCase(session.user_type)) return rows;   // 仅限管理端/staff登录

AdminTenantAccess access = adminDao.findTenantAccessByUserIdAndTenantId(session.user_id, tenantId);
if (access == null || access.getAccountAclMode() == ALL) return rows;   // 不限制
if (access.getAccountAclMode() == NONE) return List.of();               // 清空

Set<Integer> allowedIds = ...查 admin_tenant_account_access 得到的白名单...;
return rows.stream().filter(r -> allowedIds.contains(r.getId())).toList();  // CUSTOM
```

只影响这一条查询路径，不影响 `userDao`/`processDao` 被其他内部逻辑（如 `AutoRenewServiceImpl`、
`DomainServiceImpl`）直接调用的场景。

## 问题 2：一个账号被授权多个 Company 时，保存会把权限套错公司

**现象**：账号同时被授权 AP、C168、QQ，Edit User 里勾的清单保存后，切到 C168 / QQ 反而看不到数据。

**原因**：原逻辑是"遍历这个账号被授权的所有公司，把同一份勾选清单塞给每一个"。但 Account/Process 是
每个公司各自独立的表，C168 的 process id=5 和 QQ 的 process id=5 是完全不同的两条记录，
把同一份 ID 清单套到别的公司，等于套了一堆不存在的 ID，查出来自然是空的。

**修复**：`AdminServiceImpl.syncTenantGrants` 改成——本次保存的勾选清单，**只应用到当前编辑所在的那个公司**
（`scopeTenantId`）；这个账号被授权的其他公司保持原样不动：

```java
for (Integer tenantId : tenantIds) {          // 这个账号被授权的所有公司
    boolean isScopedTenant = scopeTenantId.equals(tenantId);
    access = isScopedTenant
        ? syncScopedTenantAccess(admin.getId(), tenantId, dto)   // 当前公司：应用本次勾选
        : ensureUnscopedTenantAccess(admin.getId(), tenantId);   // 其他公司：原样保留 / 新公司默认 ALL
}
```

`ensureUnscopedTenantAccess`：该公司之前已有权限记录就不动；如果是这个账号第一次被加进这家公司
（还没人配置过），默认给 `ALL`（不限制），直到管理员切到那家公司下专门编辑保存。

**使用方式**：想给不同公司设不同权限，就切到对应 Group/Company 上下文，分别打开 Edit User、
分别勾选保存。不需要任何额外 UI（中途曾加过一个公司切换标签页，已撤销，前端代码与改动前一致）。

## 问题 3：`null` 和 `[]` 被当成同一回事，全选保存后变成清空

**现象**：Account 面板全选（或从未动过、默认全选）保存后，登录看到的是空列表，跟点 Clear All 效果一样。

**原因**：前端这两种情况发送的请求体不同：
- 全选 / 从未限制过 → `accountPermissions: null`
- 点 Clear All → `accountPermissions: []`（空数组）

原来的 `resolveAclModes` 把 `null` 先强制转成空数组，之后就跟真正的 `[]` 没区别，两种语义不同的输入
被判成了同一个结果——`NONE`（一个都不给看）。

**修复**：拆开判断：

```java
private AclMode resolveAclMode(List<?> itemsRaw) {
    if (itemsRaw == null) return AclMode.ALL;                      // 全选 / 不限制
    return itemsRaw.isEmpty() ? AclMode.NONE : AclMode.CUSTOM;      // [] 清空 / 非空 自定义清单
}
```

与读取那边（`resolveAccountPermissions` 把 `ALL` 模式回显成 `null` 给前端）保持对称。

## 完整链路（修复后）

| 前端勾选状态 | 请求体 | 存的 AclMode | 登录后看到的数据 |
|---|---|---|---|
| Clear All（全部取消勾选） | `[]` | `NONE` | 空 |
| 全选 / 从未动过 | `null` | `ALL` | 全部 |
| 勾选部分 | `[{id:1},{id:2},...]` | `CUSTOM` | 只有勾选的那些 |

多公司账号：每个公司在自己的 Group/Company 上下文下独立保存，互不影响；新授权但还没设置过的公司默认 `ALL`。
