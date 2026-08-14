# Account 多公司归属 (Account ↔ Company Multi-Tenant)

## 背景

Account Edit/Add 弹窗的「Choose companies」原本用 checkbox 呈现成多选，但实际上完全没接上：

1. **候选清单混入 Group**：清单数据来自 `GET /auth/tenant-accessible`，Group 和 Company
   用同一种形状返回，前端从未过滤 `tenant_type === "GROUP"`，导致 Group（如 `OK`）会跟真正的
   Company（`OK1`/`OK2`）一起出现在勾选框里。
2. **勾选结果没有送到后端**：`selectedCompanyIds` 只是本地 UI state，仅用来渲染摘要文字；
   `saveForm()` 实际只送出单一 `scopeTenantId`（账号原本所属的那个 tenant），后端
   `UserServiceImpl.updateUser` 也只处理这一个值，`UserListDTO.tenantIds` 字段虽然存在
   （注释写着 `company.id in frontend picker`）但从未被写入或读取。

数据库层面其实已经是为多对多设计的：`account_tenant_access` 的唯一键是
`(account_id, tenant_id)`（不是每 account 一行）、`account_currency` 本来就是按
`(account_id, tenant_id, currency_id)` 独立存储、`account` 表也有注释明确写着
"account_id is unique per tenant, not globally"。`UserDao` 甚至已经有一个从未被调用过的
`findTenantIdsByUserId(id)`。本次改动是把这个已经设计好、只是没接完的功能补齐，
让一个 Account 真正可以同时属于多间 Company，而不是新发明一套模型。

修改本文档描述的任何写入路径时，**同步更新本文档**。

## 资料模型

| 表 | 归属范围 | 说明 |
|---|---|---|
| `account` | 全局唯一一行 | `name` / `role` / `password` / `status` / `payment_alert` / `remark` 等字段是**跨所有归属公司共用**的，不是每公司各自一份 |
| `account_tenant_access` | `(account_id, tenant_id)` 唯一 | 一个 account 现在可以对应多笔，代表它同时归属多间 Company |
| `account_currency` | `(account_id, tenant_id, currency_id)` 唯一 | 币种设定仍然是**每间公司各自独立**；新加入一间公司不会自动带入其他公司已设定的币种 |

## 后端实现

`UserServiceImpl.java`：

- `normalizeTenantIds(List<Integer>)`：去重 + 丢弃非正数。
- `assertCompanyTenants(List<Integer>)`：逐一用（既有但之前只在别处用到的）`TenantDao.findTenantById`
  校验每个 id 存在且 `tenantType == COMPANY`，拒绝 Group id 混进来——即使前端的过滤又失效，
  后端这层还是会挡住。
- `assertAccountCodeAvailable(tenantId, accountCode, excludeAccountId)`：复用既有的
  `findAccountIdByTenantIdAndCode`，确认该公司底下这个 account 代码没有被**另一个不同的** account 占用。
- `createUser`：从 `userListDTO.getTenantIds()` 取目标公司集合（为空时退回 `[scopeTenantId]`
  向后兼容），校验后对每个 tenant 各插入一笔 `account_tenant_access`；币种仍然只写入
  `scopeTenantId` 这一间公司。
- `updateUser`：取得目前已有的 tenant 集合（`findTenantIdsByUserId`）跟前端送来的目标集合做 diff：
  - `toAdd`：逐一做代码唯一性检查后 `insertAccountTenantAccess`。
  - `toRemove`：`deleteUserTenantAccessByAccountIdAndTenantId` + 同步清掉该公司的
    `account_currency`（避免留下指向已脱离公司的孤儿币种设定）。
  - 若 `scopeTenantId` 本身被从集合里移除（用户在编辑当下所在的那间公司把自己勾掉）：
    结果 DTO 改用集合里剩下的任一 tenant 重新查询，币种同步这一步也会跳过（该公司已经不再关联）。
  - 移除了旧的 `updateAccountTenantAccess`（`SET x=x WHERE x=x`，本质是 no-op）调用，
    以及 `UserDao` / `AccountMapper.xml` 里对应的方法定义——完全被上面的 add/remove 同步取代。
  - 全程仍在同一个 `@Transactional` 里，中途任何校验失败都会整体回滚，不会有写一半的情况。
- `findUserByTenantId`：对返回的每一行额外查一次 `findTenantIdsByUserId`，把完整的
  `tenantIds` 塞进 DTO，让前端能够知道这个 account 实际归属的**全部**公司（而不只是当前
  正在浏览的这一间）。这里是 N+1 查询，但这是内部管理后台、单一公司帐号数量不大，
  不值得为此换成批次查询。
- `deleteUserByIdAndStatus`：先删该公司的 `account_tenant_access` 与对应 `account_currency`，
  然后检查 `findTenantIdsByUserId` 是否还有剩——**只有完全没有其他公司归属时才会真的硬删除
  `account` 这一行**。这是顺手修的一个潜在 bug：`account_tenant_access` 对 `account`
  没有数据库层级的外键约束，旧逻辑不管有没有其他公司归属都会直接删掉 `account`，
  会让该账号在其他公司里的关联行悄悄变成孤儿、永久性地在那些公司里消失。

## 前端实现

`Count-frontend/src/pages/account/`：

- `AccountListPage.jsx` 的 `allCompanyButtons`：过滤条件加上
  `tenant_type !== "GROUP"`（原本只过滤 `isVirtualGroupLinkCompanyRow`）——这才是真正堵住
  Group 出现在勾选框里的地方。`groupOnlyAccountMode`（另一套「选 Group」的单选模式）不受影响。
- `accountListApi.js`：
  - `normalizeAccountListItem` 把后端的 `tenantIds` 映射成 `tenant_ids`。
  - 新增 `normalizeAccountTenantIds`（跟既有 `normalizeAccountCurrencyIds` 同款：去重 + 丢非正数）。
  - `buildAccountCreateRequest` / `buildAccountUpdateRequest` 新增 `tenantIds` 参数并放进请求体。
  - 新增 `tenantIdsToPickerCompanyIds`（既有 `tenantIdToPickerCompanyIds` 的阵列版本），
    用来把一个 account 完整的公司集合转成勾选框可以直接使用的 id 列表。
- `AccountListPage.jsx` 的 `loadSelectionMeta` / `openEdit`：`openEdit` 已经有本地的
  `row` 可用，改成把它传进 `loadSelectionMeta({ editingRow: row })`，让 Edit 模式下
  `selectedCompanyIds` 用该 account **完整**的 `tenant_ids` 预先勾选，而不是只勾当前
  浏览的那一间。Add 模式默认只预选当前公司，行为不变。
- `saveForm()`：从 `selectedCompanyIds` 组出 `tenantIds`（数字化 + 去重），非 Group-only
  模式下若为空会挡下并提示（复用既有的 `pleaseSelectCompanyFirst`），并把 `tenantIds`
  传进 `buildAccountCreateRequest` / `buildAccountUpdateRequest`。

**防止「顺手截断」多公司账号**：后端在 `tenantIds` 为空时会退回 `[scopeTenantId]`
向后兼容——这代表任何**没有主动带上完整 `tenantIds`** 的更新请求，都会把一个多公司账号
悄悄裁成只剩当前这一间公司。因此下列既有的、不是走 `saveForm()` 的更新入口也一并补上：

- `accountListApi.js` 的 `toggleAccountUserPaymentAlert`（列表上快速切换提醒开关）：
  改成带上 `row.tenant_ids`。
- `AccountListPage.jsx` 里强制解除币种链接的那段（`unlinkCurrentAccountFromCurrency`）：
  改成带上当下 `selectedCompanyIds`。
- `bankprocesslist/hooks/useBankProcessListPage.js`：这个页面自己有一套「新增/编辑
  Account」的小弹窗（`submitAccountModal` / `loadAccountModalSelectionMeta` /
  `refreshAccountModalCurrenciesIfOpen`），原本的注释写着 "Spring account create/update
  is scoped to one tenant"、每次都只塞一个 tenant id。这次一并改成：编辑时改用该
  account 的完整 `tenant_ids` 预填 `accountModalSelectedCompanyIds`，提交时把它当作
  `tenantIds` 传出去；找不到既有集合才退回单一 `scopeTenantId`。这个页面本身没有
  开放多选 UI（依然是单一入口），这里只是确保**编辑一个已经属于多间公司的账号时不会
  意外把它裁掉**，不是在这个页面新增多选功能。

## 已知限制

- `status` / `payment_alert` / `remark` 等字段在 `account` 表上只有一份，是所有归属公司
  **共用**的——把一个跨公司共用账号在某间公司停用，会影响它在所有公司的状态显示。
  这是既有 schema 设计（不是本次改动引入的），本次没有改变这个行为。
- 币种设定仍然是每间公司各自独立；新增一间公司后需要另外去该公司的 Edit 里设定币种。
- `findUserByTenantId` 每行多一次 `findTenantIdsByUserId` 查询（N+1），在这个体量下可接受。

## 相关清理：移除 `UPLINE` 兼容分支

顺带处理：`UserServiceImpl.normalizeAccountLedgerRole` 原本有
`if ("UPLINE".equals(normalized)) normalized = "SUPPLIER";` 这段历史兼容代码
（对应 `backend/src/main/resources/sql/migrate_upline_role_to_supplier.sql` 那次数据迁移）。
改动前用 `SELECT UPPER(TRIM(role)), COUNT(*) FROM account GROUP BY 1` 确认线上数据库
已经没有任何 `UPLINE` 残留，才移除这个分支。`PARTHER` → `PARTNER` 的兼容分支跟这次无关，保留不动。

前端检查过没有任何 account role 相关的 `UPLINE` 引用；仓库里唯一匹配到 "Upline" 的地方
是 `datacapture/paste/*` 底下解析外部投注平台报表列名（例如 "Upline Member Bonus"）的逻辑，
是完全不同领域的概念，未做改动。
