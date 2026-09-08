# Dashboard KPI 卡片 — 接入 Spring Boot API

> **范围**：Dashboard 页面 4 张 KPI 卡片（Profit / Expenses / Net Profit / Earnings）+ Trend Chart 走势图，
> 单一 COMPANY 类型租户、单一货币这一种最简单场景。
> **新增后端**：`DashboardController` / `DashboardService(Impl)` / `DashboardDao` + `DashboardMapper.xml` +
> `DashboardKpiDTO` / `DashboardKpiRoleAmount` / `DashboardTrendPointDTO` / `DashboardTrendRoleAmount`
> （新建文件，均在 `Count` 仓库）。
> **前端改动**：`Count-frontend` 仓库的 `useDashboardPage.js` / `dashboardRoutePrefetch.js` /
> `dashboardConstants.js` / `dashboardChart.jsx`——把 Dashboard 页面还在打的旧 PHP 接口换成 Spring，打不到
> Spring 后端的功能（Group 汇总、Company All 合并、按币种拆分的 Earnings 面板、FX 换算）UI 组件保留挂载，
> 但不再发请求，渲染成空/`-`。
> **最后更新**：2026-09-09（新增第 8 节：Trend Chart 走势图）

---

## 目录

0. [速查：每张卡片的金额怎么算出来的](#0-速查每张卡片的金额怎么算出来的)
1. [新增的后端能力](#1-新增的后端能力)
2. [前端接入](#2-前端接入)
3. [新旧 API 对照表](#3-新旧-api-对照表)
4. [业务规则确认记录](#4-业务规则确认记录)
5. [Bug 修复记录（按时间顺序）](#5-bug-修复记录按时间顺序)
6. [尚未覆盖的范围](#6-尚未覆盖的范围)
7. [KPI 卡片"较上一期"百分比对比功能](#7-kpi-卡片较上一期百分比对比功能)
8. [Trend Chart 走势图](#8-trend-chart-走势图)

---

## 0. 速查：每张卡片的金额怎么算出来的

> 这一节是给"下次回来看忘了细节"用的速查表，完整的业务规则解释和验证过程在第 1、4、5 节，这里只放结论。
> SQL 源码都在 `backend/src/main/resources/mybatis/DashboardMapper.xml`，Java 组装逻辑在
> `backend/src/main/java/com/eazycount/service/impl/DashboardServiceImpl.java`。

### Profit 卡片

```
Profit = Win/Loss桶(role=PROFIT) + Cr/Dr桶(role=PROFIT)
```

**Win/Loss 桶**（`aggregateWinLossByRole`）四类交易，都记在 `account.role='PROFIT'` 的账户上：

| 交易类型 | 记账方向 | 说明 |
|---|---|---|
| `WIN` | `account_id` 侧 `+amount` | |
| `LOSE` | `account_id` 侧 `−amount` | |
| `ADJUSTMENT` | `account_id` 侧原样 `amount` | 本身可正可负 |
| 手动 `PROFIT` 类型转账 | `account_id`(To) `−amount`，`from_account_id`(From) `+amount` | 这个交易类型跟"PROFIT角色"是两回事，别搞混 |
| RATE 中间人手续费 | `+amount`（单边行走 `account_id`；旧版两边行走 `from_account_id`） | 见下方"RATE中间人"说明 |

**Cr/Dr 桶**（`aggregateCrDrByRole`）：

| 交易类型 | 记账方向 | 说明 |
|---|---|---|
| `PAYMENT`/`CLAIM`/`CONTRA` | `account_id`(To) `−amount`，`from_account_id`(From) `+amount` | |
| `RATE`（仅换汇主腿） | 同上 To(−)/From(+) | 只认 `transactions_rate.leg1_transaction_id`/`leg2_transaction_id`，中间人手续费行不算在这里 |
| RATE 中间人手续费付款方视角 | `account_id`(付款方) `−amount` | 跟 Win/Loss 桶里中间人自己的 `+amount` 是同一笔手续费的两个视角 |
| `CLEAR` | **不计入，类型列表里压根没有它** | 业务规则确认过，不是漏写（见第 4 节） |

**RATE 中间人手续费**判定：`transactions_rate.rate_group_id` 对得上，但这笔交易**不是** `leg1_transaction_id`/`leg2_transaction_id`（不是换汇主腿本身），且 `middleman_account_id` 非空——两种历史文案格式都要认：
- 新版单边行：`description = 'RATE_MIDDLEMAN_FEE'` 或 `LIKE 'MARKUP X %'`，`from_account_id` 为空
- 旧版两边行：任意描述文字（比如 "Rate charge (x2.93) from SGD 49000.00"），`from_account_id` 是中间人账户

**通用过滤条件**（Win/Loss 桶、Cr/Dr 桶都有）：`tenant_id` 匹配、`approval_status='APPROVED'`、`transaction_date` 落在查询区间内、`currency.code` 匹配请求传入的 `currency` 参数（不同货币的金额不能直接相加，见第 5 节 Bug 2）。

**真实验证过的例子**：Company 95（tenant_id=2），MYR，2026-08-01~31 → **Profit = 71,253.36**（纯 WIN/LOSE，没有 RATE 中间人）。Company AG（tenant_id=5），MYR，2026-08-01~31 → **Profit = 279,873.94**（含 28,295 的 RATE 中间人手续费收入）。

### Expenses 卡片

```
Expenses = Win/Loss桶(role=EXPENSES) + Cr/Dr桶(role=EXPENSES)
```

跟 Profit **同一套 SQL、同一套规则**，唯一区别是 `account.role='EXPENSES'`。EXPENSES 角色的账户通常是净支出方向，所以算出来的数字天然是负数（不是代码额外加的负号，是账户本身的收支方向决定的）。

**真实验证过的例子**：Company 95 → **Expenses = −45,033.00**。Company AG → **Expenses = −96,844.00**（Win/Loss 桶 −87,500.00 + Cr/Dr 桶 −9,344.00）。

### Net Profit 卡片

```
Net Profit = profit.add(expenses)
```

**用加法，不是减法**——因为 `expenses` 本身已经是带符号的负数了，`profit − expenses` 会把负负得正、变成"利润+支出"（历史 bug，已修复，见第 5 节）。`profit.add(expenses)` 才等价于真正想要的 `profit − |expenses|`。

**真实验证过的例子**：Company 95 → `71,253.36 + (−45,033.00) = 26,220.36`。

### Earnings 卡片（第 4 张，条件显示）

```
Earnings = netProfit × percentage / 100     （RoundingMode.HALF_UP，scale=8）
```

`percentage` 怎么来：
1. **判断当前登录身份能不能有股权**：`session.user_type=="owner"` → 查 `owner_type='owner'`；`session.user_type=="user"` 且 `session.role=="partnership"` → 查 `owner_type='user'`；其他一律没有（含 `member`/账本科目账户登录）——不符合条件直接 `showEarnings=false`，卡片不显示
2. **查哪张表**：`YearMonth.from(dateTo)` 等于当前月 → 查 `tenant_ownership`（live 表）；否则查 `tenant_ownership_history`（`effective_month`=该月 1 号的快照）
3. `percentage <= 0` 或查无此行 → `showEarnings=false`

**这张卡这次没有拿真实配置过股权的账号验证过**（见第 6 节），逻辑是对的但没有实测数字核对，之后如果发现不对先从这里查起。

#### Ownership 连接算法——为什么是这个判断规则

这部分容易踩的坑是**命名撞车**：`tenant_ownership.account_id` 跟 `transactions.account_id` 完全不是同一个东西，虽然字段名一样。

| 字段 | 实际指向 | 说明 |
|---|---|---|
| `transactions.account_id` | `account` 表（`role=PROFIT/EXPENSES/CAPITAL...` 那张账本科目表） | Dashboard Profit/Expenses 算的就是这张表 |
| `tenant_ownership.account_id` | **`owner` 表或 `user` 表**的 id（取决于同一行的 `owner_type`） | 这是"股东是谁"，跟账本科目账户完全无关 |

`owner_type` 是个三选一枚举，`tenant_ownership`/`tenant_ownership_history` 都一样：

| `owner_type` | `account_id` 指向 | 什么身份能命中这一行 |
|---|---|---|
| `'owner'` | `owner.id` | 公司老板登录（`SessionUser.user_type == "owner"`） |
| `'user'` | `user.id`，且这个 user 的 `role_id` 对应 `user_role.code = 'PARTNERSHIP'` | 合伙人身份的 Admin 登录（`SessionUser.user_type == "user"` 且 `SessionUser.role == "partnership"`） |
| `'group'` | 不指向具体账户，`partner_tenant_id` 指向另一个 tenant（集团） | **这次没做**——见下面 |

这个对照表不是猜的，是查 `TenantOwnership.xml` 里 `getShareholderCandidates`（Ownership 页面"选股东"下拉框用的那条 SQL）反推出来的——那条 SQL 的候选人只有两个来源：`owner` 表（`JOIN tenant t ON o.id = t.owner_id`）和 `user` 表 `role_id` 对应 `PARTNERSHIP` 的那些行，`account` 表（账本科目账户）从来没出现过。也就是说**账本科目账户永远不可能被分配股权**，这是数据结构层面就决定的，不是权限判断加出来的限制。

**`owner_type='group'` 这条链路完全没接**：挂集团的股权配置（比如"C168 利润池 100% 划给 AP 集团"），还需要 Group Earnings tab 里的 `group_account_percentage` 做二次分配才能算出某个具体账户能拿多少——`DashboardServiceImpl` 目前只处理 `owner`/`user` 两种直接持股，`group` 这条完全没实现，也没有查真实数据验证过配了 Group 股权的场景会发生什么（大概率是直接查不到行，`showEarnings` 保持 `false`）。

---

## 1. 新增的后端能力

```
GET /api/dashboard/kpi?tenant_id=&date_from=&date_to=&currency=
```

| 参数 | 说明 |
|---|---|
| `tenant_id` | 数字 tenant.id，或者 "C168" 这种公司 code（走 `TenantDao.findTenantByCode` 解析） |
| `date_from` / `date_to` | `YYYY-MM-DD`，KPI 统计的日期区间（含首尾） |
| `currency` | **必填**，如 `MYR`。所有金额都按这一种货币过滤求和，不同货币的金额不能直接相加（见第 5 节 Bug 2） |

响应（`previous*` 字段是第 7 节新加的"上一期"对比数据）：
```json
{
  "status": "success",
  "success": true,
  "message": "",
  "data": {
    "profit": 71253.36,
    "expenses": -45033.00,
    "netProfit": 26220.36,
    "showEarnings": true,
    "earningsPercentage": 90.0,
    "earnings": 23598.32,
    "previousDateFrom": "2026-07-01",
    "previousDateTo": "2026-07-31",
    "previousProfit": 57252.19,
    "previousExpenses": -22800.00,
    "previousNetProfit": 34452.19,
    "previousEarnings": 31006.97
  }
}
```

| 层 | 文件 | 作用 |
|----|------|------|
| Controller | `backend/src/main/java/com/eazycount/controller/DashboardController.java` | 接收 4 个参数；`tenant_id` 为 code 时复用 `TenantDao.findTenantByCode` 解析成 id（跟 `TenantOwnershipController` 同款写法） |
| Service | `backend/src/main/java/com/eazycount/service/impl/DashboardServiceImpl.java` | 核心计算逻辑（见下） |
| Dao / Mapper | `backend/src/main/java/com/eazycount/dao/DashboardDao.java` + `backend/src/main/resources/mybatis/DashboardMapper.xml` | 两条聚合 SQL（Win/Loss、Cr/Dr）+ 两条股权查询（当前 / 历史） |
| DTO | `backend/src/main/java/com/eazycount/dto/DashboardKpiDTO.java`、`DashboardKpiRoleAmount.java` | 响应体 / SQL 行映射 |

### 计算逻辑

- 只支持 `tenant_type = COMPANY` 的租户；GROUP 类型的 tenant 直接返回全 null（`showEarnings=false`，其余字段 null），前端渲染成 `-`。Group 级别的汇总还没做。
- **Profit** = Win/Loss 桶(role=PROFIT) + Cr/Dr 桶(role=PROFIT)
- **Expenses** = Win/Loss 桶(role=EXPENSES) + Cr/Dr 桶(role=EXPENSES)
- **Win/Loss 桶**（`aggregateWinLossByRole`）：
  - `WIN`(+) / `LOSE`(−) / `ADJUSTMENT`(原样) 记在 `account_id` 一侧
  - 手动 `PROFIT` 类型转账：`account_id`(To) 记 `−amount`，`from_account_id`(From) 记 `+amount`
  - **RATE 中间人手续费**（两种历史格式都要认，见第 5 节 Bug 3）：
    - 单边行：`account_id`=中间人、`from_account_id` 为空、描述匹配 `RATE_MIDDLEMAN_FEE` 或 `MARKUP X %` → `+amount`
    - 旧版两边行：`from_account_id`=中间人（不检查描述文字，兼容 "Rate charge (x2.93) from SGD ..." 这种旧库迁移文案）→ `+amount`
  - 都按 `account.role` 分组求和，并且都加了 `UPPER(c.code) = UPPER(#{currencyCode})` 的币种过滤
- **Cr/Dr 桶**（`aggregateCrDrByRole`）：
  - `PAYMENT` / `CLAIM` / `CONTRA` / `RATE`（仅换汇主腿，`transactions_rate.leg1_transaction_id`/`leg2_transaction_id` 命中才算，排除中间人手续费行）：`account_id`(To) 记 `−amount`，`from_account_id`(From) 记 `+amount`
  - RATE 中间人手续费的**付款方视角**：`account_id`=付款方、`from_account_id` 非空、命中 `middleman_account_id` 关联 → `−amount`（跟上面 Win/Loss 桶里中间人自己的 `+amount` 是同一笔手续费的两个视角，一边收入一边支出，不会重复也不会漏）
  - **`CLEAR` 被有意排除**，类型列表里压根不写 `CLEAR`（见第 4 节，这是确认过的业务规则，不是待定）
  - 同样按 `account.role` 分组求和 + 币种过滤
- **Net Profit** = `profit.add(expenses)`。`expenses` 本身已经是带符号的负数（EXPENSES 角色净下来是支出方向），用加法而不是减法，否则会变成双重取反、把支出算成加法（详见第 5 节历史 bug，现已修复且验证过）。
- **Earnings**（第 4 张卡）：只反映**当前登录身份自己**的股权份额，不会看到别人的：
  - 判断身份：`session.user_type == "owner"` → 查 `tenant_ownership`/`tenant_ownership_history` 的 `owner_type='owner'` 那一行；`session.user_type == "user"` 且 `session.role == "partnership"` → 查 `owner_type='user'` 那一行；其余（含 `member`，即账本科目账户登录）一律不显示 Earnings 卡
  - 当月（`YearMonth.from(dateTo)` 等于当前月）查 `tenant_ownership`（live 表）；非当月查 `tenant_ownership_history`（按月快照，`effective_month` = 该月 1 号）
  - `percentage <= 0` 或查无此行 → `showEarnings=false`
  - `earnings = netProfit × percentage / 100`（`RoundingMode.HALF_UP`，scale=8）

---

## 2. 前端接入

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：

- **KPI 数字**（`~L1397` 的 `useEffect`）：`isSingleCompanyKpiScope`（`~L1277`）为真时才发请求——`companyId` 非空 + 不是 Group All / Company All / 不是纯 Group 账本模式 + 不是多公司 subset 合并。也就是新接口只支持"选中了具体一家公司"这一种场景，即使当前挂了个 Group 标签做导航（比如截图里 `Group ID: IG` + `Company: 95`）也算在内——因为对这一家公司自己的数字来说，Group 标签只是导航用的。
  - 请求：`GET api/dashboard/kpi?tenant_id=&date_from=&date_to=&currency=`（`currency` 用的是页面已有的"当前选中币种" state `currencyCode`）
  - 没有走 `dashboard_bootstrap_api` 那套 cache / dedup / prefetch 机制，故意做得很简单（`dashboardRoutePrefetch.js` 里对应的预热函数 `warmDashboardRouteCache` 已改成显式 no-op）
  - 消费端（`~L8734`）：`springKpiData.profit/expenses/netProfit/showEarnings/earnings` 原样透传给 KPI 卡片，前端不做二次计算（旧版 `computeKpiMetrics()`/股权乘数换算那套客户端逻辑，这条路径完全不再走）；`previous*` 字段用来建 `comparisons`（百分比对比），细节见第 7 节
- **Trend Chart 数字**：单独一个 `useEffect` 打 `GET api/dashboard/chart`，细节见第 8 节
- **Currency 选择器**：`fetchCompanyAccountCurrencyCodes(companyId)`（`~L317`）改成调 `fetchCurrencyListByTenantId()`（Spring `POST /api/currency/list?tenant_id=`），过滤掉 `status=INACTIVE` 的币种。`loadCurrencies` 主函数里原本内联直接打 `get_scope_account_currencies_api.php` 那处（`~L3010` 附近），只要选中了具体一家公司（`singleCid` 非空且不是纯 Group 账本模式）就改走这个新函数。
- **Company 切换**：`syncCompanySession()`（`~L2324`）改成调 `syncCompanySessionApi()`（`utils/company/companySessionSync.js`，本来就是项目里已经迁移好、其他页面在用的 Spring `POST /auth/switch-tenant`），不再手写一份打 PHP 的 fetch。
- 其它场景（Group 账本、Company All、Group All、多公司合并）目前**没有 Spring 后端**：
  - KPI 数字：`springKpiData` 直接清空，卡片显示为空 / `-`
  - Trend Chart：`springTrendData` 同样直接清空，`chartRows` 落到 `dashboardData`（本来就是 null）→ 空 → 零骨架兜底
  - Currency：`fetchCompanyCurrencySettingCodes()` 改成直接 `return []`，不再发请求；`loadCurrencies` 里 Company All 合并那个分支同样跳过请求，`codes` 留空
  - Earnings 按币种拆分的圆环图 + 列表、FX 汇率换算（`frankfurterRates.js`）：组件保留挂载，只是没有数据源，渲染空/零状态

---

## 3. 新旧 API 对照表

| 旧 PHP 接口 | 现状 | 说明 |
|---|---|---|
| `api/transactions/dashboard_api.php` | **已移除**（`DASHBOARD_API` 常量已删） | 单公司场景由新接口 `GET /api/dashboard/kpi` 取代；Group/多币种/Trend 相关用法没有替代品 |
| `api/transactions/dashboard_bootstrap_api.php` | **已移除**（`DASHBOARD_BOOTSTRAP_API` 常量已删） | 同上 |
| `api/transactions/get_scope_account_currencies_api.php` | **单公司场景已替换**；Group/子公司下钻/Company All 场景**已短路禁用**（调用会直接拿到 `null`，不发真实请求） | 单公司货币选择器改走 `POST /api/currency/list` |
| `api/transactions/get_company_currencies_api.php` | **已移除**（相关函数直接返回空数组，合并分支跳过请求） | 只服务于子公司下钻 / Company All 合并这两个已延后的功能，没有替代品 |
| `api/transactions/user_currency_order_api.php` | **已短路禁用**（返回 `null`，不发请求） | 币种展示顺序偏好，暂无替代品 |
| `api/session/update_company_session_api.php` | **已替换** | 改用项目里已迁移好的 `POST /auth/switch-tenant`（`syncCompanySessionApi()`） |

**这次新用到 / 新接入的 Spring 接口**：

| Spring 接口 | 用途 |
|---|---|
| `GET /api/dashboard/kpi` | **本次新建**，4 张 KPI 卡片的核心数据源 |
| `POST /api/currency/list?tenant_id=` | 已有接口，这次第一次接到 Dashboard 单公司货币选择器上 |
| `POST /auth/switch-tenant` | 已有接口（其他页面已在用），这次接到 Dashboard 的公司切换上 |
| `GET /auth/tenant-accessible` | 不受这次改动影响——Company 那一排 chip 列表本来就走这个接口（`fetchOwnerCompaniesAll`），跟 Dashboard KPI 迁移无关 |

**踩过的坑**：`POST /api/currency/available`（返回 `is_linked` 字段）**不能**用来做这个货币选择器——`is_linked` 只有传了具体 `account_id` 才有意义，不传的话后端永远返回 `false`，会导致货币列表整个消失（这次真的踩了一次，发现后改回 `/api/currency/list`）。

---

## 4. 业务规则确认记录

**`CLEAR` 类型交易被排除在 Cr/Dr 桶之外——这是确认过的业务规则，不是待定问题。**

- 技术分类上 `CLEAR` 和 `PAYMENT`/`CLAIM`/`CONTRA` 一样是 Cr/Dr 类型交易，Transaction List 页面正常显示/结清 `CLEAR`（`TransactionSearchMapper.xml` 的 `aggregateDomainPaymentCrDr`，`manualCrDrTransactionTypes` 包含 `CLEAR`）。
- 但 Dashboard 的 Profit/Expenses KPI **业务上要求永远排除 CLEAR**：`CLEAR` 通常是操作人员按账户当前 Win/Loss 余额去开的一笔"结清"交易，作用是把 Win/Loss 余额清零，不代表新增的利润/支出。
- 这条规则中途一度被误改成"把 CLEAR 加回 Cr/Dr"（第 5 节 Bug 1），排查后确认那是回归，已改回排除。

---

## 5. Bug 修复记录（按时间顺序）

### Bug 1：CLEAR 一度被错误地加回 Cr/Dr 桶

**现象**：Company 95，2026-08-01~31，Profit 卡片显示 80,145.78，但 Transaction List 同期 Total 显示 Win/Loss=71,253.36、Cr/Dr=−71,253.36（几乎全是 CLEAR）、Balance=0。

**排查**：`DashboardMapper.xml` 的 `aggregateCrDrByRole` 类型列表里被加回了 `'CLEAR'`，导致 CLEAR 交易在 PROFIT 角色这一侧产生了不该有的净额。改回排除 CLEAR。

**误诊**：中途一度怀疑是"RATE 中间人手续费没处理"，加了对应 SQL——被用户指出"没加 CLEAR 之前数据就已经不对了"，确认是诊断错误，撤回。

### Bug 2：跨货币金额直接相加（真正的根因，Bug 1 排查中发现）

**现象**：撤回 CLEAR 相关改动、只保留排除逻辑后，Profit 数字还是不对（80,145.78，预期 71,253.36）。

**排查**：直接查 `count_real` 库，tenant_id=2（公司 95）2026-08 这个月 PROFIT 角色账户按币种拆开：MYR 的 WIN−LOSE = 71,253.36（跟 Transaction List 完全一致），但账户还有 CNY/EUR/NPR/SGD/USD 的交易——`aggregateWinLossByRole`/`aggregateCrDrByRole` 当时没有按币种过滤，把 6 种货币的金额直接加总，凑出了 80,145.78。

**修复**：`currency` 参数一路加到 `Controller → Service → Dao → Mapper`，两条聚合 SQL 都 `INNER JOIN currency c` + `UPPER(c.code) = UPPER(#{currencyCode})`；前端 KPI fetch 加上 `currency: currencyCode`。用真实 SQL 验证：MYR 单独算，Profit=71,253.36、Expenses=−45,033.00，全部对上。

### Bug 3：RATE 中间人手续费遗漏（Company AG，真的需要）

**现象**：Company AG（tenant_id=5），加了币种过滤后 Profit 显示 251,578.94，预期 279,873.94，差 28,295 左右。

**排查**：这次直接查交易明细，找到 3 笔 `RATE` 类型交易（id 17299/17819/18547），`from_account_id`=4640（AG 的 PROFIT 角色账户），描述是旧版文案 "Rate charge (x2.93) from SGD 49000.00"；核对 `transactions_rate` 表确认这 3 笔的 `rate_group_id` 对应的 `leg1_transaction_id`/`leg2_transaction_id` 都不是它们自己，但 `middleman_account_id=4640`——这 3 笔是**中间人手续费行**，账户 4640 是收手续费的中间人，金额合计 28,294.99986500 ≈ 28,295，正好补齐差额。

**修复**：这次重新加回 RATE 中间人的 Win/Loss（+amount，单边行 + 旧版两边行两种格式）和 Cr/Dr（付款方视角 −amount）分支——跟 Bug 1 里"误诊"的那次不同，这次是拿到具体交易 ID、`transactions_rate` 关联关系验证过真的存在这类数据才加的。验证：Profit=279,873.94、Expenses=−96,844.00（Win/Loss −87,500 + Cr/Dr −9,344），全部对上。

### Bug 4：Currency 选择器整个消失（前端大改动的副作用）

**现象**：一次大规模前端重写（把 Dashboard 页面所有 PHP 调用换成 Spring/移除不支持的功能）之后，单公司场景的 Currency 选择器整排都不见了。

**排查**：`get_scope_account_currencies_api.php` 被那次重写短路成直接返回空——出发点是"这个接口只喂 Group/多币种功能，这次不做"，但漏看了单公司货币选择器本身也是靠**同一个** PHP 接口拿数据的，不是只有 Group 场景才用。

**修复尝试 #1（错的）**：改用 `POST /api/currency/available` 的 `is_linked` 字段过滤，结果货币列表还是空——因为 `is_linked` 只有传了具体 `account_id` 才会算，没传永远是 `false`。

**修复 #2（对的）**：改用 `POST /api/currency/list?tenant_id=`，返回该公司在 Currency Setting 里配置的完整货币列表。查库确认：公司 95 配置了 12 种 ACTIVE 货币，跟截图里的 12 个 chip 完全对上。

**收尾**：顺手把两处还在打 `get_company_currencies_api.php`（已经 404 了）的调用点也拿掉——`fetchCompanyCurrencySettingCodes()` 改成直接返回空数组，Company All 合并那个分支的请求循环也删了。

---

## 6. 尚未覆盖的范围

以下功能这次都**明确没做**，UI 组件保留挂载，但不会发请求、显示为空/`-`：

- Group 账本（group ledger）、Company All / Group All 合并视图、多公司 subset 合并场景的 KPI 计算和 Trend Chart
- Earnings 按币种拆分的圆环图 + Currency/Amount/Original Amount/Rate 明细列表
- FX 汇率换算（`frankfurterRates.js`）
- Group 级别的股权链路（`tenant_ownership.owner_type='group'`、多层集团路径连乘）——`DashboardServiceImpl` 目前只处理 `owner`/`user` 两种直接持股，`group` 那条链路完全没接

以下部分**代码已经写了，但这次没有拿真实数据交叉验证过**，如果之后发现数字不对可以从这里查起：

- 历史月份股权快照（`tenant_ownership_history`）的读取路径——`applyEarnings()`/`resolveEarningsAmount()` 里 `YearMonth.now()` 判断分支，这次验证的都是当月数据
- Earnings 卡片本身的乘数计算（`percentage`、`showEarnings` 判断）——这次验证重点在 Profit/Expenses/Net Profit，没有拿一个真实配了股权比例的账号登录测过 Earnings 卡片
- 第 7 节"较上一期"对比功能里，Earnings 那一栏的 `previousEarnings`（同样依赖没验证过的股权乘数计算）——Profit/Expenses/Net Profit 三个对比过（用第 5 节 Bug 2/3 里已经验证过的公司 95 数据核对了 7 月/8 月的 Win/Loss+Cr/Dr 桶能正常查出数），Earnings 的对比没测

---

## 7. KPI 卡片"较上一期"百分比对比功能

> 背景：截图里旧版展示的百分比里出现过 "↓999.9%" 这种数字——排查后确认是"上一期基准值很小、当期波动很大"导致真实百分比变化是几百上千%（比如 -1770%），旧代码把这类结果硬夹到 ±999.9 再显示，属于故意设计但**呈现方式会让人误以为是精确值**。这次顺手把这个显示方式改成了 `999.9+%`，明确告诉用户"这是被夹住的极端值，不是精确算出来的数字"。

### 7.1 后端：区间对齐算法

新增于 `DashboardServiceImpl#resolvePreviousRange(dateFrom, dateTo)`，一次 `/api/dashboard/kpi` 请求内部会用同一套聚合逻辑（Win/Loss桶、Cr/Dr桶、CLEAR排除、货币过滤、RATE中间人）分别对 current 区间和自动算出来的 previous 区间各查一次：

- **当前区间正好是 N 个完整自然月**（从某月 1 号到某月月末，含 N=12＝整年这种特例）→ 上一期 = 紧邻往前的 N 个完整自然月
  - 例：8/1~8/31（1个月）→ 上一期 7/1~7/31
  - 例：6/1~9/30（4个月）→ 上一期 2/1~5/31（往前推 4 个月，不是 3 个月——按区间实际月数算，不是"当前是第几个月就减几"这种拍脑袋算法）
  - 例：1/1~12/31（整年）→ 上一期 = 去年整年（这是"N个完整自然月"规则 N=12 时的特例，没有另外写整年逻辑）
- **除此之外的任意自定义区间**（不是从月初到月末）→ 按天数平移：算出当前区间总天数，上一期就是紧邻往前平移同样天数的区间

`DashboardKpiDTO` 新增字段：`previousDateFrom`/`previousDateTo`（算出来的上一期区间，给前端拼对比文案用）+ `previousProfit`/`previousExpenses`/`previousNetProfit`/`previousEarnings`。**百分比、涨跌箭头、封顶逻辑全部不在后端算**——后端只吐 raw 数字，格式化是前端的事。

`previousEarnings` 只有在 `showEarnings=true` 时才会算（复用跟当前区间一样的身份识别 + 当月/历史月份判断逻辑，只是用 `previousDateTo` 去判断该查 `tenant_ownership` 还是 `tenant_ownership_history`）；查不到对应股权配置就是 `null`，不会伪造成 0。

### 7.2 前端：百分比计算 + 封顶显示

`Count-frontend/src/pages/dashboard/lib/dashboardKpi.js`：

- `kpiPercentChange(current, previous)`：不变，还是 `(current-previous)/abs(previous)*100`，`previous=0` 时特殊处理（`current=0`→0%，否则强制 ±100%），结果夹在 ±999.9 之间
- **新增** `kpiPercentChangeIsClamped(current, previous)`：`previous≠0` 且真实算出来的百分比绝对值超过 999.9 才算 `true`（`previous=0` 那种"没有基准"的情况不算夹住，不会被标记成 `+`）
- `buildKpiCompare()` 现在多返回一个 `clamped: boolean`

`DashboardKpiCard.jsx`：`compare.clamped` 为真时，百分比数字后面拼一个 `+`——`↓999.9%` 变成 `↓999.9+%`。

`useDashboardPage.js`：
- `kpi` useMemo 里，`springKpiData.previousProfit/previousExpenses/previousNetProfit/previousEarnings` 分别过 `buildKpiCompare()` 建进 `comparisons.profit/expenses/netProfit/earnings`；`previous*` 字段是 `null`（比如 Earnings 上一期没有股权配置）就**不放进 `comparisons`**，卡片走"没有对比数据"那条展示分支，不会拿 0 硬凑一个假百分比
- **对比文案**：旧版写死 `i18n.thanLastMonth`（"较上月"），只有当前区间刚好是一整个自然月的时候才准确。新增 `isSingleWholeCalendarMonth(dateFrom, dateTo)` 判断，不是整月的情况改用 `i18n.thanPreviousPeriod`（"较上一周期"——这个 key 项目里其实早就写好中英文翻译了，只是之前没人接上用）

### 7.3 显示格式约定（跟用户确认过的设计）

| 场景 | 显示 |
|---|---|
| 正常百分比变化 | `↑12.3%` / `↓8.5%` |
| 上一期基准值很小、真实百分比被砍到 ±999.9 上限 | `↓999.9+%`（`+` 表示"封顶值，不是精确数字"） |
| 上一期是 0，当期非 0（没有基准可比） | 目前沿用旧逻辑显示 `↑100%`/`↓100%`（**这个不算精确，只是"从无到有"的占位显示**，跟"封顶"是两回事，讨论时明确说过这个不用加 `+`；如果以后想改成"N/A"/"新增"这种更诚实的显示，需要另外改 `kpi` useMemo 让 `comparisons` 对应字段整个不生成，而不是改 `kpiPercentChange` 本身） |

---

## 8. Trend Chart 走势图

> 范围跟 KPI 卡片一样：只支持单一 COMPANY 租户、单一货币。没有 Earnings 之外的按币种拆分、没有 FX 换算。

### 8.1 后端

```
GET /api/dashboard/chart?tenant_id=&date_from=&date_to=&currency=
```

响应：
```json
{
  "status": "success",
  "success": true,
  "message": "",
  "data": [
    { "date": "2026-08-01", "profit": 1762.85, "expenses": 0, "netProfit": 1762.85 },
    { "date": "2026-08-02", "profit": 40022.37, "expenses": 0, "netProfit": 40022.37 },
    { "date": "2026-08-03", "profit": 0, "expenses": 0, "netProfit": 0 }
  ]
}
```

**接口命名**：最初讨论时说的是 `dashboard/trend`，实际写代码时落地成了 `/api/dashboard/chart`（`DashboardController#getTrend` 方法名还叫 `getTrend`，只是 `@GetMapping` 路径是 `/chart`），这里以磁盘上实际生效的路径为准。

| 层 | 文件 | 作用 |
|----|------|------|
| Controller | `DashboardController.java` `getTrend()` | 路径 `/api/dashboard/chart`，参数跟 `/kpi` 完全一样（`tenant_id`/`date_from`/`date_to`/`currency`） |
| Service | `DashboardServiceImpl.java` `getTrend()` | 见下面的计算逻辑 |
| Dao / Mapper | `DashboardDao.java` `aggregateWinLossByRoleAndDate`/`aggregateCrDrByRoleAndDate` + `DashboardMapper.xml` 对应 SQL | 跟 KPI 卡片用的 `aggregateWinLossByRole`/`aggregateCrDrByRole` **完全同一套业务规则**（WIN/LOSE/ADJUSTMENT、手动PROFIT转账、RATE中间人手续费两种格式、CLEAR排除、货币过滤），唯一区别是 SQL 的 `SELECT`/`GROUP BY` 多加了 `t.transaction_date`，按"日期+role"分组，不是只按 role 分组成一个总数 |
| DTO | `DashboardTrendPointDTO`（响应用，`date`/`profit`/`expenses`/`netProfit`）、`DashboardTrendRoleAmount`（SQL 行映射用，多一个 `date` 字段） | |

**计算逻辑**：
- `tenant_type != COMPANY` → 直接返回空数组 `[]`（不是像 KPI 那样返回"全 null 的一个对象"，因为这是个数组接口，空数组就是"没有数据"最自然的表达，前端会自己落到零骨架兜底）
- 把 `aggregateWinLossByRoleAndDate`/`aggregateCrDrByRoleAndDate` 的结果 nest 成 `Map<LocalDate, Map<role, amount>>`，然后**从 `dateFrom` 循环到 `dateTo`（含首尾）**，每一天都算一次 `profit = winLoss(day,PROFIT)+crDr(day,PROFIT)`、`expenses` 同理、`netProfit = profit.add(expenses)`——**区间内哪怕某天完全没交易也会补一个全 0 的点**，不会让前端拿到的数组有洞
- 不算 Earnings（这条线是前端自己拿 KPI 卡片的 `earningsPercentage` 乘出来的，见 8.2）
- 不算"较上一期"对比（那是 KPI 卡片专属概念，走势图不需要）

**验证过的例子**：Company AG（tenant_id=5），MYR，2026-08，按日期把所有 PROFIT 金额加起来 = 279,873.94——跟 KPI 卡片验证过的总数（含 RATE 中间人手续费那 28,295，分布在 3 个不同日期）完全一致，说明按日期拆分没有破坏原来验证过的求和结果。

### 8.2 前端

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：
- 新增 `springTrendData`/`springTrendLoading` state + 独立的 `useEffect`，触发条件跟 KPI fetch 一样（`isSingleCompanyKpiScope`），打 `GET api/dashboard/chart`
- `chartRows` useMemo 分支：`isSingleCompanyKpiScope` 时用新的 `buildSpringTrendChartRows()`；其它场景继续走旧的 `buildChartRows(dashboardData, ...)`（`dashboardData` 本来就是 null，落到零骨架兜底，行为没变）

`Count-frontend/src/pages/dashboard/lib/dashboardChart.jsx` 新增 `buildSpringTrendChartRows(trendPoints, startYmd, endYmd, locale, earningsMultiplier)`：
- **没有复用**旧的 `buildChartMetricRow()`——那个函数是按旧 PHP `daily_data` 的约定写的，会把 `expenses` 当成"原始正数，前端自己转负号"（`expensesDelta > 0 ? -expensesDelta : expensesDelta`），但新接口的 `expenses` 已经是最终带符号的数字了，直接套旧函数会把符号转错一次（这次全程贯彻的"新接口出来的数字不做二次符号转换"原则，KPI 卡片那次也是同样处理）
- 按天/按月的判断和聚合逻辑复用了现成的 `shouldAggregateChartByMonth`/`eachMonthInRange`/`eachDateInRange`/`formatChartMonthLabel`（纯日期工具，不含符号假设，可以放心复用）——区间长就按月把每天的数字加总，短就直接按天显示，这个"按天转按月"的颗粒度判断规则完全没变，只是喂给它的数据源换了
- **Earnings 那条线**：走势图本身没有单独接口给这条线，是拿 `kpi.showEarnings` + `springKpiData.earningsPercentage` 算一个乘数，乘到每天的 `netProfit` 上（`earnings = netProfit × earningsMultiplier`）——**全区间用同一个百分比**，不会按每天实际的股权配置去查（如果股权在区间中途变过，这条线会不准，但这是旧版 PHP 时代就有的简化处理，不是这次新引入的偷懒，见旧版 `buildChartMetricRow` 同款逻辑）

### 8.3 尚未验证 / 已知简化

- Earnings 走势线的"全区间统一乘数"简化处理，没有拿"股权比例在区间中途变过"的真实场景测过
- `springTrendData` 没有做旧版 `paintedSummaryRef`/`scopeDataPending` 那套"冻结上一次画面直到新数据到位"的机制——切换公司/日期的一瞬间可能有极短暂的"旧数据+新日期标签"不匹配，跟 `springKpiData` 当初的简化处理是同一个决定，不是这次新增的问题
