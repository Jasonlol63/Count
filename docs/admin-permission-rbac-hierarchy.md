# Admin 权限体系 — 角色层级 + Read-Only 全局校验（2026-08-27）

> 配套前端记录：`Count-frontend/docs/permission-rbac-frontend-alignment.md`
> 相关但独立的既有文档：`docs/frontend-springboot-migration.md` 第 5 节（Admin 页面）、第 11 节（跨模块共性与缺口）——本文档记录的改动缩小了那两节里列出的部分缺口，具体见文末「对照更新」。

## 背景

在这次改动之前做过一轮审计，发现两个核心问题：

1. **`SecurityConfig` 对 `/api/**` 全部 `permitAll()`**，除 `/auth/**` 外不要求登录，也没有任何 `@PreAuthorize`/`hasRole` 之类的接口级角色校验。
2. **`read_only` 账号标志**（Partnership / Audit 角色在 UI 上可切换）只在两个地方被检查（`MaintenanceServiceImpl`、`TransactionSubmitServiceImpl`），其余所有写接口（Admin、Domain、Currency、Process、BankProcess、Announcement、AutoRenew、Ownership、DataCapture 等）完全没有防护——只要有合法 session token，绕过前端直接调接口就能写。
3. `user_role.hierarchy_level` 定义与前端 `ROLE_HIERARCHY` 互相矛盾（DB 把 Partnership 排最低，前端排第 2），且这个字段在后端从未被任何业务逻辑读取用于比较。

## 业务规则（本次落地的目标状态）

**角色层级**（数值越小权限越高，`user_role.hierarchy_level`）：

```
1 OWNER  >  2 PARTNERSHIP  >  3 ADMIN  >  4 MANAGER  >  5 SUPERVISOR  >  6 ACCOUNTANT / 7 AUDIT / 8 CUSTOMER_SERVICE
```

**全局 read_only 开关**（Partnership、Audit 两个角色，账号级独立生效）：

- `read_only=1`：该账号在**全站所有页面**禁止一切写操作（不分角色，只要标志位是 1 就拦截，与角色无关——这点沿用了原有 Maintenance/Transaction 检查的行为，只是不再各处重复实现）。
- `read_only=0`：正常按下面的层级规则执行 CRUD。

**Admin 页面（员工列表 `/api/userlist`）写操作范围**：

| 操作者 | 可管理目标（含自己按下方限制） | 不可碰 |
|---|---|---|
| OWNER | 所有角色，含自己 | — |
| PARTNERSHIP | 自己（仅基础信息，不含 role 字段）、ADMIN、MANAGER、SUPERVISOR、ACCOUNTANT、AUDIT、CUSTOMER_SERVICE | OWNER |
| ADMIN | MANAGER、SUPERVISOR、ACCOUNTANT、AUDIT、CUSTOMER_SERVICE | OWNER、PARTNERSHIP、ADMIN（含自己/同级） |
| MANAGER | 自己（仅基础信息）、SUPERVISOR、ACCOUNTANT、AUDIT、CUSTOMER_SERVICE | ADMIN 及以上 |
| SUPERVISOR | 自己（仅基础信息）、ACCOUNTANT、AUDIT、CUSTOMER_SERVICE | MANAGER 及以上 |
| ACCOUNTANT / AUDIT / CUSTOMER_SERVICE | 无 Admin 页面入口 | — |

通用规则：任何角色编辑「自己」时，`role` 字段一律锁死，不能自我提权。

## 实现

### 1. 统一权限工具类

新增 [`backend/src/main/java/com/eazycount/util/AccessControlUtils.java`](../backend/src/main/java/com/eazycount/util/AccessControlUtils.java)（放在既有 `util` 包，不是 `utils`，跟仓库现有命名对齐）：

- `requireWritable(SessionUser session)` — 未登录或 `read_only==1` 时抛 `BusinessException`。所有写方法的第一行都应调用。
- `assertCanManageAdminTarget(actor, actorHierarchyLevel, isSelf, targetHierarchyLevel, roleFieldChanging)` — Admin 页面专属的层级校验：
  1. Owner 直接放行；
  2. 操作者角色必须在 `{PARTNERSHIP, ADMIN, MANAGER, SUPERVISOR}` 集合内，否则直接拒绝（连自己都管不了，更管不了别人）；
  3. 调用 `requireWritable` 走 read_only 检查；
  4. 若是编辑自己：只挡 `roleFieldChanging`（改角色），其余放行；
  5. 否则按 `actorHierarchyLevel >= targetHierarchyLevel` 判断（数值必须严格小于目标才允许管理）。

之所以是「工具类里手动调用」而不是全局拦截器/AOP：这个项目的每个 Controller 都会自己 `catch (BusinessException e)` 并重塑成 `{success:false, message, data:null}` 返回；如果用 `HandlerInterceptor` 在进入 Controller 之前就抛异常，会绕开这层本地 catch，改走 `GlobalExceptionHandler` 的 `{status:"error", message}` 格式，破坏前端 `response.data.success` 的判断契约。所以选择跟既有 `MaintenanceServiceImpl.requireWritableSession()` 一样的写法：**在每个 Service 写方法内部第一行调用**，让异常走原有调用链，响应格式不变。仓库里目前也没有任何 `@Aspect`/`HandlerInterceptor`/`WebMvcConfigurer`，这个选择也更贴近现有代码风格。

### 2. `AdminServiceImpl` 层级校验

`createAdmin` / `updateAdmin` / `updateStatusById` / `deleteAdminByIdAndStatus` 四个方法都接入了 `assertCanManageAdminTarget`：

- 新增私有方法 `resolveRole(String role)`（返回 `AdminRole` 而不只是 id，供拿 `hierarchyLevel`）与 `resolveActorRole(SessionUser session)`。
- `resolveRoleId` 改为委托给 `resolveRole(...).getId()`，避免重复逻辑。
- `updateAdmin` 额外计算 `roleChanging`（对比 `normalizeStaffRoleCode` 后的新旧角色码）传给校验方法，用于判断自己改自己角色。

### 3. `read_only` 全局覆盖

除了 `AdminServiceImpl`，以下 Service 的**全部写方法**都在方法首行加了 `AccessControlUtils.requireWritable(session)`（复用方法里已有的 `SecurityUtils.currentUser()`/`session` 变量，不重复取）：

| Service | 方法 |
|---|---|
| `AnnouncementServiceImpl` | `addMaintenance` `addAnnouncement` `updateAnnouncement` `updateMaintenance` `deleteAnnouncement` `deleteMaintenance` |
| `AutoRenewServiceImpl` | `rejectRequest` `approveRequest` `deleteRequest` |
| `BankCountryOptionServiceImpl` | `insertNewCountry` `insertNewBankOption` `deleteCountryByIdAndTenantId` `deleteBankOptionByIdAndTenantId` |
| `BankProcessServiceImpl` | `insertBankProcess` `updateBankProcessDetails` `deleteBankProcess` `updateBankProcessStatus` `updateBankProcessRemark` |
| `BankProcessResendServiceImpl` | `resend` |
| `BankAccountingDueServiceImpl` | `skipPeriods` `postToTransaction`；`resolveInbox` 仅在 `restoreSkipped` 分支内加（该方法本身也是只读的 inbox 查询入口，只有 restore 分支才写库） |
| `CurrencyServiceImpl` | `addNewCurrency` `deleteCurrencyByIdAndTenantId` `bulkUpdateAccountCurrency` |
| `DataCaptureServiceImpl` | `saveBankDraft` |
| `DataCaptureSummaryServiceImpl` | `saveAddFormula` `updateFormula` `deleteFormulas` `submit` |
| `DomainServiceImpl` | `createDomain` `updateTenantDetailsSetting` `updateDomain` `deleteOwnerDetails` `updateDomainFeeSettings`（内部共用的 `insertOwnerDetails`/`updateOwnerDetails`/`updateTenantDetails`/`deleteTenantDetails` 等 helper 不重复加，避免和外层入口重复校验） |
| `ProcessServiceImpl` | `addNewProcess` `updateProcess` `deleteProcessById` `updateProcessStatus` |
| `ProcessDescServiceImpl` | `insertNewProcessDescription` `deleteProcessDescriptionById` |
| `TenantOwnershipServiceImpl` | `linkPartner` `saveOwnership` `updateTenantParentId`（这三个方法本来就有 `canModifyOwnership()` 角色/权限检查，这次是叠加 read_only 检查，两者互不冲突） |
| `UserServiceImpl` | `createUser` `updateUser` `updateStatusByUserId` `deleteUserByIdAndStatus` `insertAccountLink` `deleteAccountLinkById` `deleteAccountLinkByAccountId` `deleteAccountLinkByPair` `updateAccountLink` |
| `MaintenanceServiceImpl` / `TransactionSubmitServiceImpl` | 原有的两处独立 read_only 检查改为调用 `AccessControlUtils.requireWritable`，逻辑不变，只是去重 |

**明确跳过、未加的地方**（都有具体理由，不是遗漏）：

- `MaintenanceController` 的写接口本来就全部走 `requireWritableSession()`，没有缺口。
- `MemberController`、`TransactionController`（除 submit 外）只有读接口。
- 一些被多处复用的底层 helper（如 `CurrencyServiceImpl.insertAccountCurrency`）没有单独加检查，因为调用它的所有入口方法都已经在各自入口加了，重复加反而是无意义的双重校验。

### 4. `SecurityConfig` 兜底

`/api/**` 从 `.anyRequest().permitAll()` 改成 `.anyRequest().authenticated()`（`PUBLIC_URLS` 里的登录/登出/重置密码几个接口不受影响）。这只是防止**完全匿名**（连 session token 都没有）的请求，真正的角色/层级判断仍然在上面第 2、3 点的 Service 层。

### 5. 数据库迁移

新增 [`backend/src/main/resources/sql/migrate_role_hierarchy_and_admin_permission_fix.sql`](../backend/src/main/resources/sql/migrate_role_hierarchy_and_admin_permission_fix.sql)（幂等，可重复执行）：

- 把 `user_role.hierarchy_level` 改成本文档开头的新顺序（Partnership 从 8 改到 2）。
- 删除 `user_role_permission` 里 Customer Service 对应 `ADMIN`（员工列表）侧边栏权限的行（如果存在——实际检查下来 `schema.sql` 本身从未插入过这行，这条 DELETE 是防御性的，万一线上库有手动加的脏数据也能顺手清掉）。

`schema.sql` 本身的基线也同步改了（新建库直接生效），`TABLE_MIGRATION.md` 索引表加了这条迁移脚本的说明行。

## 已知的、故意没在这次处理的点

- **Ownership 页面**（`/api/ownership`）：`TenantOwnershipServiceImpl.canModifyOwnership()` 判断「role==owner 或 session.permissions 含 ownership」，这次只是叠加了 read_only 检查，没有改动它本身的角色判断逻辑。Admin 角色现在前端也会显示 Ownership 入口（见前端文档），后端这条判断本来就认 Admin（`schema.sql` 默认给 Admin 发了 `OWNERSHIP` 权限），所以后端不用改。
- **通用的「API 层校验 session.permissions 是否含对应模块」**（`docs/frontend-springboot-migration.md` 第 11.1/11.5 节提到的缺口）**没有全面解决**——本次只针对 Admin 页面做了角色层级校验、针对 read_only 做了全局覆盖，像"Process 接口要求 session.permissions 包含 process"这类更通用的模块级权限校验仍然缺失，属于更大范围的另一个任务。

## 对照更新

`docs/frontend-springboot-migration.md` 第 33 节（Login → Permission → 各业务页面功能说明）第 5.12、11.1、11.5 小节里，以下几行随本次改动更新：

- 5.12「API 层校验 `permissions` 含 `admin`」：仍是 ❌（这次做的是角色层级校验，不是「session.permissions 是否含 admin」这个具体检查），但新增了一行「Admin 页面按角色层级校验写操作」✅ 及「Partnership/Audit read_only 全局校验」✅。
- 11.1/11.5：新增说明「read_only 与 Admin 页面层级校验已于 2026-08-27 补上，通用模块级 permission 校验仍缺」。
