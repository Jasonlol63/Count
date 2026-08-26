# Member 页面 Win/Loss 报表 — Account Link + Mini Grid Spring 迁移

对应 [`docs/frontend-springboot-migration.md`](frontend-springboot-migration.md) §7.7 记录的计划：
Member 页面 boot 流程、Account Link 判断、以及"有 link 时多账号同时展示"的 mini grid，这次全部
迁移到了 Spring。这份文档记录整个过程，包括中途尝试过又撤销的方案。

状态：后端 + 前端全部完成，已经用真实账号做过多轮浏览器实测并修了几个实测才暴露出来的 bug（见下）
（2026-08-26）。

## 现状总览

- `useMemberPageShell.js`（boot：身份/头像/公告/登出/到期提醒）：已迁移到 Spring
  （`/auth/current-user` + `/auth/tenant-accessible`）。
- `useMemberWinLoss.js` + `memberWinLossApi.js`（Account Link 判断、currency 归属、Win/Loss
  报表、mini grid 余额）：已迁移到 Spring（`/api/member/*` + `/api/transaction/search`）。
- `MemberPage.jsx` 是**唯一**入口，`App.jsx` 的 `member` 路由直接指向它——中途曾经拆过一个独立的
  "无 Account Link 简化页面"方案，后来撤销了，细节见下方「撤销的方案」。
- mini grid（有 Account Link 时多账号同时展示）这套 UI 本身**没有重新设计**，只是把它内部打的接口
  从 PHP 换成了 Spring；没有 Account Link 时它自然只显示当前账号自己（`linkedAccounts` 长度为 1）。

## Account Link 可见性规则（不变）

- **Bidirectional**：双方账号互相都能看到对方。
- **Unidirectional**：只有 `source_account_id` 那一方能看到对方，反向不行——比如 A1 是
  source、单向链到 C1，A1 能看到 C1，但 C1 看不到 A1。
- 直接复用 `UserServiceImpl.getAllLinkedAccounts`（`docs/frontend-springboot-migration.md` §4.4
  已迁移的 Account Link 能力），后端和前端都没有重新实现这套判断逻辑。

## 后端：`/api/member/*`

- `backend/.../dto/MemberPageDTO.java`（新建，原名 `MemberProfileDTO`，后改名——见下方「命名/
  DTO 组织的几轮调整」）：`accountId/accountCode/accountName`、`tenantId`、`hasAccountLink`，
  以及若干嵌套静态类（不再拆成独立顶层文件）：
  - `LinkedAccount {id, accountCode, name}` —— Account 选择器一行
  - `AccountCurrencies {accountId, currencies}` —— 批量 currency 响应一行
  - `AccountCurrencyBalance {accountId, currency, balance}` —— mini grid 批量余额响应一行
  - `BatchRequest {accountIds, currencyCodes, dateFrom, dateTo}` —— `account-currencies/batch`
    和 `mini-grid-balances` **共用**的请求体（前者只用 `accountIds`，其余字段直接忽略）
- `backend/.../service/UserPageService.java` + `impl/UserPageServiceImpl.java`：
  - `getMemberProfile()`：`hasAccountLink` 为 true 时返回 `linkedAccounts`（自己+所有可见账号），
    为 false 时返回 Company 信息 + 自己选过的 currency。全部从 `SecurityUtils.currentUser()`
    取 session 的 `user_id`/`tenant_id`，不接受外部参数。
  - `getMemberHistory(TransactionHistoryRequest)`：直接复用现成的
    `TransactionHistoryService.historyList(...)`。请求里的 `accountId` 是"想查看哪个账号"，由
    `resolveViewableAccountId(session, requestedAccountId)` 鉴权：不传/传自己直接放行，传别的账号
    必须出现在 `getAllLinkedAccounts(登录账号, tenantId)` 里，否则抛 `Unauthorized account
    access`。`tenantId` 永远用 session 里的值。
  - `getMemberAccountCurrencies(Integer accountId)`：同样走 `resolveViewableAccountId` 鉴权，
    内部复用 `CurrencyService.findAvailableCurrencies(tenantId, accountId)`，过滤
    `isLinked==true` 的部分。
  - `getMemberAccountsCurrencies(List<Integer> accountIds)`（批量版）：跟单账号版一样的鉴权规则，
    但只查一次 `visibleAccountIds(session)`（内部封装了 `getAllLinkedAccounts` 调用）复用给整个
    批次，而不是每个 id 各查一次；越权的 id 直接从结果里丢弃，不报错中断整批。
  - `getMemberMiniGridBalances(MemberPageDTO.BatchRequest)`（批量版）：对每个（鉴权通过的）账号
    复用现成的 `transactionHistoryService.historyList(...)`，从返回的 `history` 行里按币种取
    最后一行的 `balance` 作为期末余额，没有另外写聚合逻辑。
- `backend/.../controller/MemberController.java`：四个端点**全部是 `POST` + `@RequestBody`**
  （用户明确要求，不接受任何 URL query string 暴露 accountId/tenantId）：
  - `GET /api/member/profile`（唯一保留 GET 的——不需要参数，纯读 session）
  - `POST /api/member/history`（body：`TransactionHistoryRequest`，`dateFrom/dateTo/
    currencyCodes` + 可选 `accountId`）
  - `POST /api/member/account-currencies`（body 直接就是一个 JSON 数字，即 `accountId` 本身，
    不是 `{accountId: ...}` 包一层对象；可选，不传默认查登录账号自己）
  - `POST /api/member/account-currencies/batch`（body：`MemberPageDTO.BatchRequest`）
  - `POST /api/member/mini-grid-balances`（body：`MemberPageDTO.BatchRequest`）

这些端点这次**全部接上了前端**（mini grid 迁移，见下）。

## boot 流程迁移（`useMemberPageShell.js`）

**关键发现**：Spring 后端连的是 `testcount` 库（`application.yml`），旧版 PHP 连的是
`u857194726_c168site`（`count168test/includes/config.php`）——完全不同的数据库；`utils/auth/
authApi.js` 头部注释明确写了"Spring Boot auth APIs only — no PHP paths"，登录已经 100% 走 Spring
`/auth/login`（JWT），不会再产生 PHP session。也就是说 `current_user_api.php` 里
`session_start()` 读 `$_SESSION['user_id']` 这一步，在当前登录体系下永远判定"未登录"——
`useMemberPageShell.js` 原来打的 PHP 端点，**在当前环境下已经是打不通的死代码**，这次改动是把本来
就走不通的东西修好，不是拿一个正常工作的功能冒险。

字段映射原则（用户明确要求）：**统一用 `tenantId`，不再模拟旧版 `company_id`/`group_id` 两个字段
分开、`login_scope==='group'` 分支判断那一套**——Spring 的 `Tenant` 从设计上就是 GROUP 和 COMPANY
共用一个 id 空间，新接口全部只认一个 `tenantId`。

- `normalizeSessionUserToMemberMe(u)`：`/auth/current-user` 的 `SessionUser` → 旧代码字段名。
  `member_login_account_id`/`member_winloss_view_account_id` 统一等于 `user_id`（新设计下不再有
  独立的"当前查看账号" session 状态）；`company_id`/`company_code` 直接等于 `tenant_id`/
  `tenant_code`，不管 tenant 是 GROUP 还是 COMPANY。`expiration_hint`/`expiration_status`/
  `days_until_expiration` 用已有的 `buildSidebarExpirationFields(expirationDate)`
  （`utils/expiration/expirationReminder.js`，注释原话"Mirror `current_user_api.php` sidebar
  expiry fields"）补齐，不用改后端。
- `normalizeTenantAccessibleToCompanies(rows)`：`/auth/tenant-accessible?all=1` 的
  `{tenant_id, tenant_code, tenant_type, ...}` → 旧版 `get_account_companies` 的
  `{id, company_id, company_code}` 形状，GROUP/COMPANY 类型都原样保留，不筛选。
- 顺手把 `performLogout`（→ `authApi.js` 的 `logoutSession()`）和维护模式轮询
  （→ `fetchCurrentUser()`）也换掉了。维护模式检测依赖 PHP 专有的 `maintenance_gate.php`，Spring
  没有等价机制，轮询还在但不会再触发维护模式横幅——这跟迁移前的实际状态一致（迁移前也从来没触发过）。

## mini grid 迁移（`useMemberWinLoss.js` + `memberWinLossApi.js`）

### 状态模型简化：`companyId + groupId` → 单一 `tenantId`

跟 boot 迁移同一个原则。旧代码里 `scopeQueryFields(compId, gid)` 根据登录是 company 还是 group
决定往 PHP 传 `company_id` 还是 `group_id`，这次整个删掉，`useMemberWinLoss` 内部只有一个
`tenantId` 状态。为了不用到处改调用方，hook 返回值里保留了 `companyId: tenantId` 这个别名字段
（`MemberPage.jsx` 和 `PaymentHistoryExportPdfModal` 还在用 `companyId` 这个名字），但 `groupId`
已经不存在，`MemberPage.jsx` 传给导出 PDF 弹窗的 `groupId` 固定给空字符串（那个弹窗本身没有迁移，
见下方「未迁移」）。

`switchCompany` 因此语义也变了：以前是调 `update_company_session_api.php` 改 PHP session；现在
调 Spring 现成的 `/auth/switch-tenant`（`authApi.js` 的 `switchSessionTenant`）改 JWT 里的
`tenant_id`，因为 `/api/member/*` 全部认 session 里的 `tenant_id`，不接受客户端传参覆盖。

`switchAccount`（切换查看哪个 linked 账号）反而变简单了：不再需要
`update_account_session_api.php` 这次服务器往返，因为"查看哪个账号"现在是随请求带的参数
（`resolveViewableAccountId` 每次校验），纯前端 `setViewAccountId` 即可。

### 接口对照

| 旧 PHP 端点 | 新 Spring 调用 | 备注 |
|---|---|---|
| `account_link_api.php?action=get_all_linked_accounts` | `GET /api/member/profile`，取 `linkedAccounts` | 只在 `hasAccountLink` 为 true 时有值，`false` 时返回空数组（等价于"就自己一个"） |
| `account_currency_api.php?action=get_account_currencies` | `POST /api/member/account-currencies`（body 是裸 `accountId` 数字） | 字段名不同：旧版 `currency_id`/`currency_code`，新版 `id`/`code`，`memberWinLossApi.js` 里做了映射。当前 `viewAccountId` 已经在 mini grid 批量结果里时，`loadOwnedCurrencies` 会直接从 `linkedAccountCurrenciesMap` 取，不再打这个接口——只有"没有 Account Link"（`linkedAccountCurrenciesMap` 本来就是空的）时才真的发请求。这个 effect 还额外绑定了 `linkedDataReady`，避免跟批量请求赛跑导致去重判断落空（见下「时序 bug」小节） |
| `account_currency_api.php?action=get_batch_account_currencies` | `POST /api/member/account-currencies/batch`（body：`{accountIds}`） | 一开始按"linked 账号数量通常不多"的理由决定不建批量端点，改成并行发多个单账号请求；后来发现浏览器 Network 面板上一次进页面能看到十几次请求，用户明确要求真正做成一次批量请求，于是补建了这个端点，后来又要求改成 POST+body（不接受 URL query string 暴露 id） |
| `transactions/history_api.php`（报表主表格） | `POST /api/member/history` | **顺带简化**：旧版按币种拆分成多次请求（0/1/多币种三种分支），Spring 的 `currencyCodes` 本来就是数组，一次请求带全部选中币种即可，`fetchMemberHistory` 因此从三分支合并成一条路径。这个接口只服务当前查看账号自己的完整逐笔明细，跟下面 mini grid 余额是两回事，不能合并 |
| `transactions/history_api.php`（mini grid 每个账号+币种一格的期末余额） | `POST /api/member/mini-grid-balances` | 同上「批量接口」——旧版/迁移初版都是按 (账号, 币种) 组合逐个请求，现在一次批量请求把所有缺失的组合一起拿回来，后端内部对每个账号复用 `historyList(...)` 取每个币种最后一行 balance，不返回完整逐笔明细（比 `/history` 轻量很多） |
| `transactions/search_api.php?target_account_id=`（currency 兜底来源） | `POST /api/transaction/search` | 只在 `ownedCurrencies`/`linkedAccountCurrenciesMap` 都推不出币种时才会用到这个兜底；响应里没有旧版的数字 `currency_id`，所以这条来源填充的排序值是 `null`，不影响主流程（排序主要靠 owned/linked currency 来源） |
| `transactions/user_currency_order_api.php`（currency 拖拽排序持久化） | 不打后端，改用 `utils/company/currencyDisplayOrder.js` 的 `persistCurrencyDisplayOrder`/`readCurrencyDisplayOrder`（localStorage） | 沿用项目里其它已迁移页面（`transactionApi.js` 的 `getUserCurrencyOrder`/`saveUserCurrencyOrder`）同样的做法：Spring 本来就没有 per-user 排序 API |
| `session/update_company_session_api.php` | `POST /auth/switch-tenant?tenant_id=`（`authApi.js` 的 `switchSessionTenant`） | 复用现成端点，不是新建的 |
| `session/update_account_session_api.php` | 无需请求，纯前端 `setViewAccountId` | 见上「状态模型简化」 |

### 排查过的一个真实 bug：`/api/transaction/search` 的跳过条件

第一轮优化时，为了避免每次搜索都无条件打一次 `/api/transaction/search`（它只是极端情况下的
currency 兜底来源，正常情况下用不到），用 `ownedCurrencies.length > 0`（当前查看账号自己是否已经
有 currency）作为"要不要跳过"的判断条件。这个判断条件跟 mini grid 真正用来算币种列表的
`availableCurrencies` 不是同一套逻辑——在某些时序下 `ownedCurrencies` 已经就绪但
`linkedAccountCurrenciesMap`（mini grid 依赖的那个）还没就绪，会导致 `availableCurrencies` 算出
空列表，mini grid 直接跳过所有余额请求（表现为 history 请求数从 ~6 次骤降到 1 次，但 mini grid
实际是空的，只是主表格因为传空 `currencyCodes` 等于不筛选，还能整表显示，掩盖了问题）。

修复：直接判断 `availableCurrencies.length` 本身，不再用 `ownedCurrencies` 这个代理条件。用户拿
真实账号测过，mini grid 数据和调用次数都恢复正常。

### 排查过的另一个真实 bug：currency 拖拽排序，表格显示顺序跟拖拽结果相反

用户在 currency 筛选那排 pill 上把 SGD 拖到 MYR 前面，mini grid 表头立刻正确显示"SGD | MYR"，但
下面报表区块却还是先显示"Currency: MYR"再显示"Currency: SGD"，顺序反了。

根因：`persistCurrencyOrder(nextOrder)` 在 `setCurrencyOrder(nextOrder)` 之后**紧接着同步调用**
`fetchMemberHistory()`——但 `fetchMemberHistory` 是一个 `useCallback`，它捕获的 `availableCurrencies`
来自上一次渲染（`setCurrencyOrder` 触发的重渲染这时候还没发生），所以这次调用用的还是拖拽前的旧
顺序。mini grid 表头之所以是对的，是因为它是渲染时直接从最新 state 算出来的 `availableCurrencies`，
不经过这个有延迟的函数调用链。

修复：`fetchMemberHistory` 新增一个 `selectionOverride.currencyOrder` 入参，`commitTableDisplayContext`
和 mini grid 用的 `getMemberMiniGridCurrencies` 都改用这个"调用方直接传入的顺序"（没传的话才退回读
`availableCurrencies`）。`persistCurrencyOrder` 调用时把刚拖拽出来的 `nextOrder` 直接当参数传进去，
不再依赖那个还没来得及刷新的 state。`onCurrencyAll`/`onCurrencyToggle` 这两个不涉及改
`currencyOrder` 本身，没有这个问题，不用改。

### Company/Group 展示：从"只有多个才显示"改成"只要有一个就显示，并按 tenant 类型换文案"

- `MemberPage.jsx` 里 Company 那一行原来是 `companies.length > 1` 才渲染——单 group/单 company
  账号（`companies` 数组只有 1 条）就整行不显示，导致用户看不出自己当前在哪个 group/company 下。
  改成 `companies.length > 0`。
- 验证过：登录本身就要求 `account_tenant_access` 里有匹配的 tenant 行（`findMemberByAccountId
  AndTenantCode` 的 JOIN 条件），所以哪怕是"单 group、不属于任何 company"的账号，登录用的这个
  group 自己也一定会在 `companies` 列表里出现，不会是空数组。
- 那一行的文案原来写死 `t("company")`（"Company:"），现在按当前激活的 tenant 类型动态选
  `"group"`/`"company"` 两个 key（新增了 `group`/`集团：` 翻译）——`normalizeTenantAccessibleToCompanies`
  之前把 `/auth/tenant-accessible` 返回的 `tenant_type` 丢掉了，这次补上，`MemberPage.jsx` 用
  `companies.find(c => c.company_id === companyId)?.tenant_type` 判断。

### 澄清：unidirectional 非 source 方登录看不到 mini grid，是设计行为不是 bug

有一次用户在测试时发现某个账号登录后 mini grid 区域整个不渲染，一度怀疑是 bug。查证后确认：那个
账号是某条 unidirectional link 的**非 source 方**——按最初就定好的可见性规则，非 source 方查
`getAllLinkedAccounts` 只会拿到自己，`hasAccountLink` 为 false，`linkedAccounts` 是空数组，
`showMiniRail`（`linkedAccounts.length > 0 && ...`）自然是 false，回落到"没有 Account Link"的单
账号展示。这是规则本身决定的正确行为，没有改代码。

### 命名 / DTO 组织的几轮调整

- `MemberProfileDTO` 中途被（外部的自动格式化/重构工具，不是这次对话里的改动）改名成了
  `MemberPageDTO`，全代码库引用同步更新，验证过没有遗漏、编译通过，直接接受了这个改名。
- 用户要求把这次新开的几个 Member 专属 DTO 全部收进 `MemberPageDTO` 当嵌套静态类（不再另开顶层
  文件）：`MemberAccountCurrenciesDTO`→`AccountCurrencies`、`MemberAccountCurrencyBalanceDTO`→
  `AccountCurrencyBalance`、`MemberBalancesRequest`/`MemberAccountIdsRequest`→合并成一个
  `BatchRequest`（原本两个类字段有严格子集关系，合并成一个避免重复）。
- `LinkedAccount`/`AccountCurrencies`/`AccountCurrencyBalance` 这三个字段互不相同，讨论过后确认
  不能再合并——尝试把它们全塞成 `MemberPageDTO` 顶层字段也不行，因为它们分别是"列表"或"另一个
  接口的请求体"，不是 `/api/member/profile` 响应本身的一部分，JSON 列表天然需要一个类型描述每一
  行长什么样，没法拆成散装标量字段。

### 已知简化 / 未覆盖的边角

- **`/api/transaction/search` 目前没有校验请求里的 `tenantId` 是否等于 session 自己的
  `tenant_id`**——这是这个已迁移端点本身的既有行为（不是这次改动引入的新问题），如果要收紧，需要
  单独跟后端确认要不要加这层校验，不在这次改动范围。
- currency 排序值（`currencySortOrderRef`）的其中一个旧来源（`search_api.php` 返回的数字
  `currency_id`）现在拿不到了，只剩下 owned/linked currency 接口能提供排序值——实际影响很小，因为
  正常情况下币种排序主要就是靠这两个来源，`search` 只是极端兜底场景。
- 导出 PDF（`PaymentHistoryExportPdfModal.jsx`）**完全没有触碰**，它内部自己的 currency 拉取逻辑
  还在打旧 PHP（如果实际点开导出会失败），这次不在范围内。

## 撤销的方案（仅作记录，当前代码里不存在）

中途曾经按"无 Account Link 展示 Company + 单账号简化报表，有 link 时才展示 mini grid"这个思路，
新建过 `MemberSimpleReportPage.jsx`（简化报表页）+ `MemberPageGate.jsx`（按 `hasAccountLink` 分流
的路由组件）+ `memberProfileApi.js`（专用 API 层），也做完并构建通过了——但后来 mini grid 本身也
迁移到了 Spring，两条路径分开维护的必要性没有了，所以这三个文件**已经删除**，`App.jsx` 的
`member` 路由重新指回 `MemberPage.jsx`，现在只有一个 Member 页面，`hasAccountLink` 为 false 时
mini grid 会自然只显示当前账号自己一个。

## 尚未做的部分

- 还没测过的组合：一个有 bidirectional link 的账号（account 选择器 + mini grid 数据是否正确）；
  `switchCompany` 在真的有多个 tenant 可切的账号上是否正常（目前测过的都是单 tenant）。
- 导出 PDF（`PaymentHistoryExportPdfModal`）没有迁移，点开会走旧 PHP，预期会失败。
- 维护模式横幅在 Member 页面暂时失效（迁移前也是失效状态）。

## 涉及文件

- `backend/src/main/java/com/eazycount/dto/MemberPageDTO.java`（原 `MemberProfileDTO`，现收纳了
  全部 Member 批量接口的请求/响应嵌套类）
- `backend/src/main/java/com/eazycount/service/UserPageService.java`
- `backend/src/main/java/com/eazycount/service/impl/UserPageServiceImpl.java`
- `backend/src/main/java/com/eazycount/controller/MemberController.java`
- `Count-frontend/src/pages/member/useMemberPageShell.js`
- `Count-frontend/src/pages/member/useMemberWinLoss.js`
- `Count-frontend/src/pages/member/memberWinLossApi.js`
- `Count-frontend/src/pages/member/MemberPage.jsx`（`companyId`/`groupId` 字段清理、Company/Group
  动态 label）
- `Count-frontend/src/translateFile/pages/memberTranslate.js`（新增 `group`/`集团：` 翻译）
- `Count-frontend/src/App.jsx`
