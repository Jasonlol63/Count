# Dashboard KPI 卡片 — 接入 Spring Boot API

> **范围**：Dashboard 页面 4 张 KPI 卡片（Profit / Expenses / Net Profit / Earnings）+ Trend Chart 走势图，
> 单一 COMPANY 类型租户 **以及** 同一 Group 标签下的 "Company: All" 多公司汇总，单一货币这一种最简单场景。
> **新增后端**：`DashboardController` / `DashboardService(Impl)` / `DashboardDao` + `DashboardMapper.xml` +
> `DashboardKpiDTO`（内嵌 `RoleAmount` 静态类）/ `DashboardTrendPointDTO`（内嵌 `RoleAmount` 静态类）
> （新建文件，均在 `Count` 仓库）。
> **前端改动**：`Count-frontend` 仓库的 `useDashboardPage.js` / `dashboardRoutePrefetch.js` /
> `dashboardConstants.js` / `dashboardChart.jsx` / `loginScope.js`——把 Dashboard 页面还在打的旧 PHP 接口换成
> Spring，打不到 Spring 后端的功能（Group-All 跨组合并、多公司 subset 合并、按币种拆分的 Earnings 面板、
> FX 换算）UI 组件保留挂载，但不再发请求，渲染成空/`-`。
> **最后更新**：2026-09-10（新增第 10.8 节：Group Trend Chart——跟 Group KPI 同一套算法拆到逐天；
> 第 8.1.1 节：Company 模式 Trend Chart 的 Earnings 线改成按月精确查股权%，不再是整个区间一个百分比
> 顶到底，`DashboardTrendPointDTO` 新增 `earnings` 字段；Bug 6（Group Profit 永远算出 0，因为
> `groupKpiCompanyTenantIds` 借用了 Company: All 专属的"排除 C168"规则）已真机验证修复生效）

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
9. [Company: All 多公司汇总](#9-company-all-多公司汇总)
10. [Group KPI：Group 自己视角的 KPI 卡片](#10-group-kpigroup-自己视角的-kpi-卡片)

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
| DTO | `backend/src/main/java/com/eazycount/dto/DashboardKpiDTO.java`（含内嵌静态类 `DashboardKpiDTO.RoleAmount`） | 响应体 / SQL 行映射，原本 `DashboardKpiRoleAmount.java`/`DashboardTrendRoleAmount.java` 两个独立文件已合并成内嵌静态类，DTO 从 4 个文件减到 2 个 |

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
- **Company: All（同一 Group 下多公司汇总）** 已接上 Spring，细节见第 9 节，不再是"没有后端"的场景。
- 其它场景（纯 Group 账本、Group-All、多公司 subset 合并）目前**仍然没有 Spring 后端**：
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
| `GET /api/dashboard/kpi` | 4 张 KPI 卡片的核心数据源（单一公司） |
| `GET /api/dashboard/chart` | Trend Chart 走势图数据源（单一公司，按天），见第 8 节 |
| `GET /api/dashboard/kpi-all` | Profit/Expenses/Net Profit 多公司汇总，见第 9 节 |
| `GET /api/dashboard/chart-all` | Trend Chart 多公司汇总（按天），见第 9 节 |
| `GET /api/dashboard/group-kpi` | Group 自己视角的 4 张 KPI 卡片，见第 10 节 |
| `GET /api/dashboard/chart-group` | Group 自己视角的 Trend Chart（按天），见第 10.8 节 |
| `POST /api/currency/list?tenant_id=` | 已有接口，这次第一次接到 Dashboard 单公司货币选择器上 |
| `POST /auth/switch-tenant` | 已有接口（其他页面已在用），这次接到 Dashboard 的公司切换上 |
| `GET /auth/tenant-accessible` | 不受这次改动影响——Company 那一排 chip 列表本来就走这个接口（`fetchOwnerCompaniesAll`），跟 Dashboard KPI 迁移无关；但第 9 节 "Company: All" 复用的正是这个接口已经做对的权限过滤（见第 9.3 节） |

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

### Bug 5：Group 账本自己的 Currency 选择器整排空白（做完第 10 节 Group KPI 之后才发现）

**现象**：截图里 `Group ID: AP`、没选任何子公司（也就是在看 AP 这个 Group 自己的 KPI），Currency 那一排 chip 完全不显示——但去 Ownership/Maintenance 页面查过，AP 自己的 Currency Setting 里明明配了 `MYR`。KPI 数字也全是 0.00（这是连带效应：没有 `currencyCode` 就凑不齐 `/api/dashboard/group-kpi` 请求的必填参数，请求根本不会发出去）。

**排查**：这条链路走的是 `useDashboardPage.js` 里那个巨大的 `loadCurrencies`-类函数，"选中了具体一家公司"（`singleCid` 非空）会走 Bug 4 里修好的 Spring `fetchCompanyAccountCurrencyCodes()`；但"直接看 Group 自己账本、没选子公司"这个场景（`groupLedgerOnly`/`groupOnlyCurrencyScope` 分支）之前一直落到 `usesSpringSingleCompanyCurrency = Boolean(singleCid) && !groupLedgerOnly` 这一句判断的 **`false`** 分支——也就是继续打 `get_scope_account_currencies_api.php` 这个旧 PHP 接口。而这个接口在更早一次"删掉 Dashboard 页面用不到的 PHP 调用"的清理里已经被禁用（`fetchScopeCurrenciesDeduped` 那处直接写死 `async () => null`，代码注释也明确写了"has no Spring equivalent... disabled (no backend)"）——当时这么处理是合理的，因为那时候 Group 账本压根没有 Spring 后端，属于"确认过的未覆盖范围"，不是遗漏。

**根因一句话**：Group 自己的 Currency 选择器一直在打一个已经被判了死刑的 PHP 接口，只是因为当时 Group KPI 还没做，这个死链路没人注意到。

**修复**：现在 Group KPI 已经有 Spring 后端了（第 10 节），而 Group 在 `tenant` 表里也是自己一行、有自己的 `tenant_id`——那就没有理由继续绕去那个死掉的 PHP 接口，直接复用 Bug 4 里已经验证过的同一个函数 `fetchCompanyAccountCurrencyCodes(tenantId)`（背后就是 `POST /api/currency/list?tenant_id=`），只是这次传的 `tenantId` 是 Group 自己的 id，不是某个 Company 的 id。`useDashboardPage.js` 里新增一段：`groupOnlyCurrencyScope` 为真时，用 `companyRowIsGroupEntity()`（跟第 10 节 `groupKpiTenantId` 用的是同一个判断函数）从 `companies` 列表里找出 Group 自己那一行、取它的 `id`，构成 `groupLedgerTenantId`；只要这个 id 存在，就走 Spring 接口而不是 PHP 接口。

**验证**：这处改动写完后端已确认编译通过、前端 `vite build` 也编译通过，但**这次没有实际登录 AP 账号点开页面肉眼验证 chip 真的显示出来**——理论上应该好了（跟单公司货币选择器是同一个函数、同一条 Spring 接口），但严格说还停留在"代码逻辑推导正确"，没有做真机验证，如果之后发现还是空的，从这段代码（`useDashboardPage.js` 里 `groupLedgerTenantId`/`usesSpringGroupLedgerCurrency` 那两个变量）开始查。

### Bug 6：Group Profit 永远算出 0——`groupKpiCompanyTenantIds` 借用了 Company: All 专用的"排除 C168"规则

**现象**：AP 这个 Group 底下唯一的子公司是 C168，Ownership 页面 "Account Ownership" 标签页也确认配置了 "Group: AP 10%" 这一行（C168 分了 10% 股权给 AP）。但不管选哪个日期区间（包括真的有流水、用真实数据手算过 NetProfit ≈ −7,868.19 的 8/1~9/30 这种区间），AP 页面的 Profit/Expenses/Net Profit/Earnings 4 张卡片全部显示 `0.00`，"较上一期"对比也全是 `↑0.0%`——不是"没数据"的空状态（那种会显示 `-`），是真的算出了一个"合法"的 0。

**排查过程**：
1. 先怀疑是 Ownership 配置问题或者选的日期没流水——查真实 `count_real` 数据库（注意：第一轮查询一开始连错了库，AP/C168 的 tenant_id 在测试库和 `count_real` 里编号完全不同，容易踩坑，教训是**查这类问题必须显式指定 `database` 参数，不能依赖默认库**），确认：C168 的 tenant_id=1、AP 的 tenant_id=32，`tenant_ownership`（live 表）里 C168 确实有一行 `owner_type='group', partner_tenant_id=32, percentage=10`，配置本身是对的。
2. 用户换成 8/1~9/30 这个真的有流水的区间重新测，结果还是全部 `0.00`——排除了"单纯选到没流水的那一天"这个可能性，确认是代码逻辑问题，不是数据或时间选择问题。
3. 顺着 `computeGroupProfit()` 需要的两个输入（子公司自己的 Win/Loss+Cr/Dr、子公司分给 Group 的股权百分比）网上查：股权百分比那条查询逻辑没问题；再查前端传给后端的 `company_tenant_ids` 参数到底是怎么来的——`groupKpiCompanyTenantIds`（第 10.6 节）当初图省事直接复用了第 9 节 "Company: All" 现成的 `resolveMergeCompanyList()`。
4. 顺着这个函数网下挖：`resolveMergeCompanyList()` → `resolveGroupAllMergeCompanyList()` → `isExcludedFromGroupAggregate(companyRow, groupIds, { allowC168: false })`——找到真正的根因：
   ```js
   // sharedCompanyFilter.js
   if (!allowC168 && code === "C168") return true;  // 排除掉
   ```
   **"Company: All" 这个功能一直是故意把 C168 排除在外的**（`allowC168: false` 是写死的），这是 Company: All 自己的历史业务规则（C168 大概率因为某些特殊原因不该被算进"所有子公司加总"里，这次没有深挖 Company: All 当初为什么要排除它，只确认了这条规则确实存在、而且是故意的，不是 bug）。

**根因一句话**：Group KPI 的"这个 Group 下有哪些子公司"列表，不该跟 Company: All 共用同一个函数——Company: All 那边"排除 C168"的业务规则被一起带了进来，而 AP 底下唯一的子公司恰好就是 C168，一排除列表就是空的，`computeGroupProfit()` 一看子公司列表为空直接返回 `BigDecimal.ZERO`，不会报错也不会是"没数据"的空状态，所以看起来像是"算出来就是 0"而不是"某个环节没查到东西"，更难第一时间联想到是子公司列表被过滤空了。

**修复**：`groupKpiCompanyTenantIds`（`useDashboardPage.js`）不再调用 `resolveMergeCompanyList()`，改成直接用 `companiesForCompanyPicker(companies, selectedGroup, groupIds)`——这是 Dashboard 页面 "Company" 那一排 chip 选择器本身在用的函数，不带 Company: All 那条 `allowC168` 排除规则，C168 在这里就是一家正常公司——再过一遍标准的 `filterCompaniesForDashboardApiAccess()` 权限过滤，跟其它场景的过滤方式保持一致。

**教训**：两个功能"看起来需要同一份数据"（都是"这个 Group 下的子公司列表"）不代表可以直接共用同一个解析函数——尤其是那个函数内部还带着只对其中一个功能成立的业务规则（这里是"排除 C168"）时，复用前应该确认清楚函数内部有没有夹带只对原场景成立的假设。

**验证**：这处改动只做到前端 `vite build` 编译通过，**用户还没有重新刷新页面用真实数据肉眼确认修好**（上一条 Bug 5 也是同样的验证状态）——按 8/1~9/30 这个区间，理论上应该能看到 Group Profit 接近手算的 −786.82（C168 NetProfit −7,868.19 × 10%），如果之后验证下来数字对不上，从 `groupKpiCompanyTenantIds`（10.6 节）和 `computeGroupProfit()`（10.4 节）这两处继续查。

---

## 6. 尚未覆盖的范围

以下功能这次都**明确没做**，UI 组件保留挂载，但不会发请求、显示为空/`-`：

- **Group-All**（同时合并 AP+IG 两个 Group 一起看，也就是"Group ID: All"）、多公司 subset 合并场景的 KPI 计算和 Trend Chart——这两种没有单一的 Group tenant_id 可用，第 10 节的 Group KPI/Trend Chart 接口暂时管不到；"同一 Group 标签下 Company: All"（见第 9 节）、"单个 Group 自己的 KPI + Trend Chart"（见第 10 节，含 10.8）这两种已经做了
- "Company: All" 场景（第 9 节）下的 Currency 选择器——这次只做了 Profit/Expenses/Net Profit/Trend 的数字，货币选择器那边 `fetchCompanyCurrencySettingCodes()` 还是直接返回空数组，没有验证 Company:All 模式下切换货币会不会正常工作
- Earnings 按币种拆分的圆环图 + Currency/Amount/Original Amount/Rate 明细列表
- FX 汇率换算（`frankfurterRates.js`）
- Group 级别的股权链路（`tenant_ownership.owner_type='group'`、多层集团路径连乘）——`DashboardServiceImpl` 目前只处理 `owner`/`user` 两种直接持股，`group` 那条链路完全没接
- "Company: All" 场景下的 Earnings 卡片、"较上一期"对比——`getKpiForCompanies()`/`getTrendForCompanies()` 都没有算这两样（不是漏做，是这次明确商量好不做，见第 9.1 节）

以下部分**代码已经写了，但这次没有拿真实数据交叉验证过**，如果之后发现数字不对可以从这里查起：

- 历史月份股权快照（`tenant_ownership_history`）的读取路径——`applyEarnings()`/`resolveEarningsAmount()` 里 `YearMonth.now()` 判断分支，这次验证的都是当月数据
- Earnings 卡片本身的乘数计算（`percentage`、`showEarnings` 判断）——这次验证重点在 Profit/Expenses/Net Profit，没有拿一个真实配了股权比例的账号登录测过 Earnings 卡片
- 第 7 节"较上一期"对比功能里，Earnings 那一栏的 `previousEarnings`（同样依赖没验证过的股权乘数计算）——Profit/Expenses/Net Profit 三个对比过（用第 5 节 Bug 2/3 里已经验证过的公司 95 数据核对了 7 月/8 月的 Win/Loss+Cr/Dr 桶能正常查出数），Earnings 的对比没测
- 第 10 节 **Group KPI 全部逻辑**（Group Profit 加权汇总、Group Expenses、Group Earnings）——只做了后端编译通过 + 前端 `vite build` 编译通过，没有拿真实配置过"公司分配股权给 Group"的数据登录跑一遍数字核对，也没有实机打开浏览器验证 Group 页面渲染正常（见第 10.7 节）
- Bug 5 里 Group 账本 Currency 选择器的修复——只是代码逻辑上"应该对了"，没有真机登录 AP/IG 账号肉眼确认 chip 真的显示出来

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

响应（`earnings` 字段是后面按月精确算 Earnings 那次改动新加的，见 8.1.1）：
```json
{
  "status": "success",
  "success": true,
  "message": "",
  "data": [
    { "date": "2026-08-01", "profit": 1762.85, "expenses": 0, "netProfit": 1762.85, "earnings": 1586.57 },
    { "date": "2026-08-02", "profit": 40022.37, "expenses": 0, "netProfit": 40022.37, "earnings": 36020.13 },
    { "date": "2026-08-03", "profit": 0, "expenses": 0, "netProfit": 0, "earnings": 0 }
  ]
}
```

**接口命名**：最初讨论时说的是 `dashboard/trend`，实际写代码时落地成了 `/api/dashboard/chart`（`DashboardController#getTrend` 方法名还叫 `getTrend`，只是 `@GetMapping` 路径是 `/chart`），这里以磁盘上实际生效的路径为准。

| 层 | 文件 | 作用 |
|----|------|------|
| Controller | `DashboardController.java` `getTrend()` | 路径 `/api/dashboard/chart`，参数跟 `/kpi` 完全一样（`tenant_id`/`date_from`/`date_to`/`currency`） |
| Service | `DashboardServiceImpl.java` `getTrend()` | 见下面的计算逻辑 |
| Dao / Mapper | `DashboardDao.java` `aggregateWinLossByRoleAndDate`/`aggregateCrDrByRoleAndDate` + `DashboardMapper.xml` 对应 SQL | 跟 KPI 卡片用的 `aggregateWinLossByRole`/`aggregateCrDrByRole` **完全同一套业务规则**（WIN/LOSE/ADJUSTMENT、手动PROFIT转账、RATE中间人手续费两种格式、CLEAR排除、货币过滤），唯一区别是 SQL 的 `SELECT`/`GROUP BY` 多加了 `t.transaction_date`，按"日期+role"分组，不是只按 role 分组成一个总数 |
| DTO | `DashboardTrendPointDTO`（响应用，`date`/`profit`/`expenses`/`netProfit`）+ 内嵌静态类 `DashboardTrendPointDTO.RoleAmount`（SQL 行映射用，`date`+`role`+`amount`） | |

**计算逻辑**：
- `tenant_type != COMPANY` → 直接返回空数组 `[]`（不是像 KPI 那样返回"全 null 的一个对象"，因为这是个数组接口，空数组就是"没有数据"最自然的表达，前端会自己落到零骨架兜底）
- 把 `aggregateWinLossByRoleAndDate`/`aggregateCrDrByRoleAndDate` 的结果 nest 成 `Map<LocalDate, Map<role, amount>>`，然后**从 `dateFrom` 循环到 `dateTo`（含首尾）**，每一天都算一次 `profit = winLoss(day,PROFIT)+crDr(day,PROFIT)`、`expenses` 同理、`netProfit = profit.add(expenses)`——**区间内哪怕某天完全没交易也会补一个全 0 的点**，不会让前端拿到的数组有洞
- 不算"较上一期"对比（那是 KPI 卡片专属概念，走势图不需要）
- **Earnings 这条线**：见下面 8.1.1——这是后来单独补的一次改动，最初上线时这条线是前端自己拿 KPI 卡片的 `earningsPercentage` 乘出来的（8.2 节还留着当时的说明，标注了是旧做法）

**验证过的例子**：Company AG（tenant_id=5），MYR，2026-08，按日期把所有 PROFIT 金额加起来 = 279,873.94——跟 KPI 卡片验证过的总数（含 RATE 中间人手续费那 28,295，分布在 3 个不同日期）完全一致，说明按日期拆分没有破坏原来验证过的求和结果。

#### 8.1.1 后续改动：Earnings 走势线改成"按月精确算"，不再是"整个区间一个百分比顶到底"

**起因**：最初做 Group Trend Chart（第 10.8 节）的时候，讨论 Group Profit 那条线要不要按月分别查股权百分比（子公司分给 Group 的股权配置可能中途变过），用户明确要求"按月算，不要用查询区间末尾那一天的百分比顶到底"，某个月没配置过就当 0% 处理。既然 Group 那边要按月精确算，Company 模式 Trend Chart 原本"Earnings 那条线用一个百分比顶到底"的简化处理（8.2 节写的那个旧做法）就显得不一致了，所以这次**顺带把 Company 这边也改成同一套按月算**的逻辑，两边共用一套代码。

**DTO 改动**：`DashboardTrendPointDTO` 顶层加了 `earnings` 字段（跟 `profit`/`expenses`/`netProfit` 平级）。身份本身不具备股权资格（member/账本科目登录）时整条线是 `null`（不发这条查询）；身份具备资格但某个月没配置过股权，那个月按 0% 处理（数字是 `0`，不是 `null`）。

**新增 Dao 方法** `findOwnershipPercentagesByMonths(tenantId, accountId, ownerType, effectiveMonths)`（`DashboardDao.java` + `DashboardMapper.xml`）：一个身份、一个 tenant（可以是公司也可以是 Group，因为 `tenant_ownership_history` 本来就不区分 `tenant_id` 指向哪一种），批量查一批历史月份的股权% ——一条 `effective_month IN (...)` 查完，**不按月份循环发 SQL**。当前月不在这条查询范围内，还是走现成的 `findLiveOwnership`（live 表）。

**Service 改动**（`DashboardServiceImpl.java`）：
- 新增私有方法 `resolveOwnershipPercentagesByMonth(tenantId, accountId, ownerType, dateFrom, dateTo)`：先算出区间横跨哪些自然月，当前月走 `findLiveOwnership`，其余月份一条 `findOwnershipPercentagesByMonths` 批量查完，拼成 `Map<YearMonth, BigDecimal>`
- 新增私有方法 `applyTrendEarnings(points, percentageByMonth)`：遍历每个 Trend 点，按"这一天所在的月份"去 Map 里查百分比（查不到就当 `BigDecimal.ZERO`），算出 `earnings = netProfit × percentage / 100` 塞回点里
- `getTrend()`：`buildTrendPoints()` 算完 Profit/Expenses/NetProfit 之后，`resolveOwnerType()` 不是 `null`（身份具备股权资格）才调用上面两个方法补 Earnings；`resolveOwnerType()==null` 时整条线保持 `null`，不发这条查询
- `getTrendForCompanies()`（Company: All）**没有改动**——继续不算 Earnings，跟第 9 节的既有决定一致

**前端改动**：`buildSpringTrendChartRows()` 不再接 `earningsMultiplier` 参数，改成直接读每个点的 `earnings` 字段；按月聚合时把每天的 `earnings` 加总（不是拿月末一个百分比重新乘一次）；如果某个点 `earnings` 是 `null`（身份不具备资格），聚合结果也是 `null`，不会被误算成 0。详见第 10.8 节（Group Trend Chart 那次改动一起做的）。

**这次没做/没验证的部分**：
- 没有拿"股权比例在区间中途真的变过"的真实场景测过按月算出来的数字是否正确——逻辑上应该对（现在每个月单独查表），但没有真实数据交叉验证
- 只做到前后端编译通过，没有真机打开浏览器确认 Earnings 走势线渲染正确

### 8.2 前端（初版实现，Earnings 部分已被 8.1.1 取代）

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：
- 新增 `springTrendData`/`springTrendLoading` state + 独立的 `useEffect`，触发条件跟 KPI fetch 一样（`isSingleCompanyKpiScope`），打 `GET api/dashboard/chart`
- `chartRows` useMemo 分支：`isSingleCompanyKpiScope` 时用新的 `buildSpringTrendChartRows()`；其它场景继续走旧的 `buildChartRows(dashboardData, ...)`（`dashboardData` 本来就是 null，落到零骨架兜底，行为没变）

`Count-frontend/src/pages/dashboard/lib/dashboardChart.jsx` 新增 `buildSpringTrendChartRows(trendPoints, startYmd, endYmd, locale, earningsMultiplier)`：
- **没有复用**旧的 `buildChartMetricRow()`——那个函数是按旧 PHP `daily_data` 的约定写的，会把 `expenses` 当成"原始正数，前端自己转负号"（`expensesDelta > 0 ? -expensesDelta : expensesDelta`），但新接口的 `expenses` 已经是最终带符号的数字了，直接套旧函数会把符号转错一次（这次全程贯彻的"新接口出来的数字不做二次符号转换"原则，KPI 卡片那次也是同样处理）
- 按天/按月的判断和聚合逻辑复用了现成的 `shouldAggregateChartByMonth`/`eachMonthInRange`/`eachDateInRange`/`formatChartMonthLabel`（纯日期工具，不含符号假设，可以放心复用）——区间长就按月把每天的数字加总，短就直接按天显示，这个"按天转按月"的颗粒度判断规则完全没变，只是喂给它的数据源换了
- ~~**Earnings 那条线**：走势图本身没有单独接口给这条线，是拿 `kpi.showEarnings` + `springKpiData.earningsPercentage` 算一个乘数，乘到每天的 `netProfit` 上（`earnings = netProfit × earningsMultiplier`）——**全区间用同一个百分比**~~（**已过时，见 8.1.1**——这条线现在是后端按月精确算好、直接吐每天的数字，前端不再自己乘百分比）

### 8.3 尚未验证 / 已知简化

- ~~Earnings 走势线的"全区间统一乘数"简化处理，没有拿"股权比例在区间中途变过"的真实场景测过~~（**已改成按月精确算，见 8.1.1**，但按月算出来的数字同样没有拿真实场景交叉验证过）
- `springTrendData` 没有做旧版 `paintedSummaryRef`/`scopeDataPending` 那套"冻结上一次画面直到新数据到位"的机制——切换公司/日期的一瞬间可能有极短暂的"旧数据+新日期标签"不匹配，跟 `springKpiData` 当初的简化处理是同一个决定，不是这次新增的问题

---

## 9. Company: All 多公司汇总

> 范围：**只支持"当前选中的 Group 标签下所有公司"这一种"All"**——比如 `Group ID: IG` + `Company: All`，
> 汇总的是 IG 组下面的那几家公司（95/AG/CX/RS/VG），不属于 IG 的公司（AP 组的、或没分组的 C168）不算进去。
> 不支持 Group-All（跨多个 Group 合并）、多公司 subset 合并、纯 Group 账本这几种更复杂的场景，见第 6 节。

### 9.1 需求原话

> "这个 All 的功能是将对应的 currency 数据 + 所有公司的 Profit, Expenses 进行汇总然后再相减就得到了 Net Profit。
> 比如我选择的 Currency 为 MYR, 那么就只会拿所有公司的 MYR Profit 数据进行汇总以及 Expenses 金额数据，
> 然后再用 Total Profit − Total Expenses = Total NetProfit"——只要在当前 Group 下所有 Company，不在当前
> Group 的（比如 AP、C168）不进入 All 范围；Earnings 卡片先不管；Trend Chart 逻辑跟单公司一样，只是把
> "一家公司每天的流水"换成"这个 Group 下所有公司每天流水的总和"。

### 9.2 设计决策：为什么不在后端重新判断"哪些公司属于这个 Group"

这次专门讨论过这一点：后端**不**自己去查 `tenant.parent_id` 之类的字段反推"IG 组下有哪些公司"，而是让前端把已经算好、并且**已经做了权限过滤**的公司 id 列表直接传过来（`GET .../kpi-all?tenant_ids=1,2,3&...`），后端只管照单加总。原因：

1. **避免出现第二套"谁能看哪家公司"的判断逻辑**。前端 `resolveMergeCompanyList()` 内部本来就会跑 `filterCompaniesForDashboardApiAccess()`（就是不久前修过 JK 账号权限 bug 的那个函数），如果后端自己重新用 `tenant.parent_id` 查一遍"IG 组下所有公司"，两边的口径万一将来改岔了（比如某个 admin 的权限被收回、某公司被移出 Group），后端算出来的汇总数字就可能包含这个用户本不该看到的公司，属于数据泄漏风险。
2. **旧系统卡顿的教训**：怀疑旧版是"每家公司、甚至每种货币各发一次请求"（比如 5 家公司 × 3 种货币 = 15 次请求），这次改成前端只发**一次**请求，后端用一条 SQL（`tenant_id IN (...)`）把所有公司的数字直接在数据库里加总，不在应用层循环、也不用多次网络往返。

### 9.3 后端

```
GET /api/dashboard/kpi-all?tenant_ids=1,2,3&date_from=&date_to=&currency=
GET /api/dashboard/chart-all?tenant_ids=1,2,3&date_from=&date_to=&currency=
```

`tenant_ids` 是逗号分隔的数字 tenant.id 列表，不支持公司 code（跟 `/kpi`/`/chart` 那两个单公司接口不一样，那两个还支持传 "C168" 这种 code 走 `resolveTenantId()` 解析——`kpi-all`/`chart-all` 直接要求数字 id，因为调用方（前端）本来就是从 `companies` 数组里拿现成的数字 `id`，不需要再走一次 code 查找）。

**核心改动：`DashboardDao` 两组聚合方法的 `tenantId: Integer` 全部泛化成 `tenantIds: List<Integer>`**——单公司场景传 `List.of(tenantId)`（只有 1 个元素），"All"场景传一批。四个方法全部改了：

| 方法 | 用途 | Mapper 里的改动 |
|---|---|---|
| `aggregateWinLossByRole` | KPI 卡片 Win/Loss 桶 | `WHERE t.tenant_id = #{tenantId}` → `WHERE t.tenant_id IN (...)` |
| `aggregateCrDrByRole` | KPI 卡片 Cr/Dr 桶 | 同上 |
| `aggregateWinLossByRoleAndDate` | Trend Chart Win/Loss 桶 | 同上，多按 `transaction_date` 分组 |
| `aggregateCrDrByRoleAndDate` | Trend Chart Cr/Dr 桶 | 同上 |

**CASE WHEN 判断逻辑、CLEAR 排除、RATE 中间人手续费、货币过滤这些规则一个字都没改**——只是把 `tenant_id = ?` 换成 `tenant_id IN (...)`，数据库自然会把多家公司的行放在一起求和，不需要额外的"按公司分组再在 Java 里加一遍"这一步。

`DashboardServiceImpl` 新增：
- `getKpiForCompanies(tenantIds, dateFrom, dateTo, currencyCode)`：复用 `computeProfitExpenses()`（跟 `getKpi()` 内部用的是同一个私有方法，只是这次传进去的是多元素列表），返回 `DashboardKpiDTO`，只填 `profit`/`expenses`/`netProfit`，`showEarnings` 固定 `false`，**不算"较上一期"对比**（这两样都不是这次要的，`previous*` 字段全部留空）
- `getTrendForCompanies(tenantIds, dateFrom, dateTo, currencyCode)`：跟 `getTrend()` 复用同一个新抽出来的私有方法 `buildTrendPoints()`（原本 `getTrend()` 里"按天补 0"那段循环直接抽出来共用，不用两份一样的代码）

`Net Profit = profit.add(expenses)`（不是减法）——跟单公司那套的约定完全一致，`expenses` 汇总完还是负数，加法即可，不需要另外判断"多公司汇总时符号会不会不一样"（不会，每家公司自己的 `expenses` 已经是负的，负数加负数还是负数）。

### 9.4 验证过的例子

拿 Company 95（tenant_id=2，已验证 Profit=71,253.36）+ Company AG（tenant_id=5，已验证 Profit=279,873.94，含 RATE 中间人手续费 28,295）一起传 `tenant_ids=2,5`，MYR，2026-08：

- **KPI 汇总**：Profit = 351,127.30（= 71,253.36 + 279,873.94，分毫不差），Expenses = −132,533.00（= −45,033.00 + −87,500.00）
- **按天汇总**（Trend）：同样的 `tenant_id IN (2,5)` 条件按 `transaction_date` 分组后再加总，Profit 总和还是 351,127.30，Expenses 总和还是 −132,533.00——**说明按公司合并、按日期拆分这两个维度互不干扰，怎么切都是同一个总数**。

### 9.5 前端

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：

- **范围判断**：`groupAllMode`（项目里现成的 state，语义就是"Company: All 且已经选中了某个 Group 标签"）
- **公司 id 列表**：新增 `groupAllTenantIds`（`useMemo`），直接复用现成的 `resolveMergeCompanyList()` 拿到当前 Group 下、当前登录身份有权限看的公司行，再取 `.id`。这个函数内部已经在跑 `filterCompaniesForDashboardApiAccess()`，所以取出来的列表本身就是权限过滤过的（呼应 9.2 的设计决策）
- **KPI**：新增 `springKpiAllData`/`springKpiAllLoading` state + `useEffect`，`groupAllMode` 为真时打 `GET api/dashboard/kpi-all`；`kpi` useMemo 加了 `groupAllMode` 分支，直接读 `springKpiAllData.profit/expenses/netProfit`，`showEarnings=false`，`comparisons={}`（这个视图没有"较上一期"对比，卡片自然不会显示涨跌箭头）
- **Trend**：新增 `springTrendAllData`/`springTrendAllLoading` state + `useEffect`，打 `GET api/dashboard/chart-all`；`chartRows` useMemo 加了 `groupAllMode` 分支，**复用跟单公司完全同一个** `buildSpringTrendChartRows()` 构建函数（Earnings 乘数固定传 `0`，因为这个场景没有 Earnings）——这个函数本来就不关心数字是一家公司算出来的还是好几家公司加总算出来的，不需要为"All"另外写一份
- `kpiLoading` 加上了 `groupAllMode && springKpiAllLoading` 的判断，卡片 loading 状态跟 KPI-all 请求对上

### 9.6 尚未覆盖 / 未验证

- **Currency 选择器**：这次只做了金额数字，`fetchCompanyCurrencySettingCodes()`（groupAllMode 下货币列表的来源之一）之前已经被短路成直接返回空数组，这次没有去验证 Company:All 模式下切换货币这个交互本身还能不能正常工作——如果测出来货币选不了或选了没反应，从这里查起
- **Earnings 卡片**、**"较上一期"对比**：`getKpiForCompanies()`/`getTrendForCompanies()` 都没有算，是这次明确商量好先不做的，不是漏了
- 只验证过 2 家公司加总（95+AG）的场景，没有测过 5 家公司同时加总，理论上 SQL `IN (...)` 加再多个 id 都是同一个查询模式，但没有拿真实的 5 家公司数据跑过一遍对总数

---

## 10. Group KPI：Group 自己视角的 KPI 卡片

> 范围：Dashboard 页面切到某一个 **Group 标签本身**（比如截图里 `Group ID: AP`、`Company` 那一排没有选中任何一家）
> 时应该看到的 4 张 KPI 卡片。**跟第 9 节的 "Company: All" 完全是两回事，不要搞混**：
> - 第 9 节 Company: All = "把 IG 组下面所有子公司自己的流水加总"，数字来自**子公司的账本**。
> - 第 10 节 Group KPI = "AP/IG 这个 Group 自己作为股东，能拿到多少钱"，Profit 来自**子公司利润 × 分给它的股权比例**，Expenses 来自 **Group 自己的账本**（Group 在 `tenant` 表里也是自己一行，可以有自己的流水）。
> Chart（走势图）最初这一版**明确没做**，只做了 KPI 卡片；后来单独补上了，见 10.8。

### 10.1 需求原话 / 算法拆解

用户给了两张 Ownership 页面截图（"Account Ownership" 标签页 + "Group Earnings" 标签页）加文字说明，拆出来的算法：

- **Group Profit** = Σ（这个 Group 名下**每一家子公司自己的 Net Profit** × 该公司在 Ownership 页面 "Account Ownership" 标签页里、"Group: IG" 那一行配置的**股权百分比**）。哪家公司分配给这个 Group 的比例是 0%，就贡献 0，不需要特殊处理，加起来自然是 0。
- **Group Expenses** = 直接从 **Group 自己的流水**（`tenant.tenant_type='GROUP'` 那一行自己名下的 `transactions`）里查，用跟 Company 模式**一模一样**的 Win/Loss + Cr/Dr 规则（CLEAR 排除、RATE 中间人手续费、货币过滤全部照搬），只是 `tenant_id` 换成 Group 自己的 id。如果 Group 自己确实没有任何流水，这一项就是 0——按公司那一套逻辑处理即可，不用另外判断"这是个 Group 所以要怎样"。
- **Group Net Profit** = Group Profit + Group Expenses（`Expenses` 本身带负号，用加法，跟 Company 模式的约定完全一致）。
- **Group Earnings** = Group Net Profit × 当前登录身份在**这个 Group 里**的持股百分比——对应 Ownership 页面 "Group Earnings" 标签页配置的那一行，也就是 `tenant_ownership` 表里 `tenant_id` = **Group 自己的 id**（不是某个公司的 id）的那一行。

用户确认过的三点边界条件：
1. 历史月份 live/history 表的切换规则（当月查 live 表，非当月查月度快照表）对 Group Profit 用到的"公司→Group 股权百分比"查询、Group Earnings 用到的"个人在 Group 里的持股百分比"查询**两个都适用**，逻辑完全一致。
2. 跟第 9 节 Company: All 一样，**按币种区分，不做多币种合并**——只算当前选中币种这一种。
3. 如果 Group 自己的 tenant 确实没有任何流水，Group Expenses（以及理论上 Group 自己账本能产出的其它数字）就直接是 0，把 Group 当成一家"暂时没流水的公司"处理即可，不需要额外的空值判断分支。

### 10.2 设计决策：性能——为什么最后只用了 2 条额外 SQL + 0 次额外网络请求

用户提前问了"旧版切换 Group/Company 视图时响应很慢"这个历史包袱，讨论后定的方案：

- **不是**："查一批子公司的 Net Profit"→"查一批股权百分比"→"再拿两批结果去数据库里 JOIN 一次算出最终结果"（3 条 SQL）。
- **而是**：只查 2 条新 SQL——① 一条 `tenant_id IN (...)` 批量查出这个 Group 下**每一家**子公司自己的 Win/Loss+Cr/Dr（`aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant`，见 10.3），② 一条 `tenant_id IN (...)` 批量查出这些公司分别分给这个 Group 多少股权（`findGroupEquityPercentages`，见 10.3）。**加权求和这一步在 Java 里用一个 for 循环做**，不是第三条 SQL——因为这时候数据量已经很小了（子公司数量最多几十家，每家一行数字），在应用层循环遍历比再发一次 JOIN 查询更直接、也更容易看懂。
- Group Expenses、Group Earnings 直接**复用**已经写好、验证过的单公司逻辑（`computeProfitExpenses()`、`applyEarnings()`/`resolveEarningsAmount()`），只是把参数换成 Group 自己的 tenant id——不重新发明一套。
- 最终请求数：跟 Company 模式、Company: All 模式**完全一样的模式**——KPI 卡片 1 次 GET（`/api/dashboard/group-kpi`），以后做 Chart 会是另外 1 次 GET（`/api/dashboard/chart-group`，这次没做）。不会因为"这个 Group 下有 5 家子公司"就发 5 次或更多请求。

### 10.3 后端：Dao / Mapper

沿用第 9 节"前端负责算好、已经过权限过滤的公司 id 列表，后端只管照单加总/查询"的原则——`companyTenantIds`（这个 Group 下有权限看的子公司列表）由前端传，后端不自己去反查 `tenant` 表判断"哪些公司属于这个 Group"（避免出现第二套权限口径，见第 9.2 节同样的理由）。

`DashboardDao.java` 新增 4 个方法：

| 方法 | 用途 | 说明 |
|---|---|---|
| `aggregateWinLossByRoleAndTenant(tenantIds, ...)` | 批量查每家子公司自己的 Win/Loss 桶 | 跟 `aggregateWinLossByRole` **规则完全一样**（WIN/LOSE/ADJUSTMENT、手动PROFIT转账、RATE中间人两种格式、货币过滤），唯一区别：SQL 的 `SELECT`/`GROUP BY` 多加了 `t.tenant_id AS tenantId`，按"公司+role"分组返回，**不是**像 `aggregateWinLossByRole` 那样把所有公司加总成一个数——因为这里需要保留每家公司各自的数字，才能分别乘上各自的股权百分比 |
| `aggregateCrDrByRoleAndTenant(tenantIds, ...)` | 批量查每家子公司自己的 Cr/Dr 桶 | 同上，对应 `aggregateCrDrByRole` |
| `findGroupEquityPercentages(companyTenantIds, groupTenantId)` | 批量查"这批公司各自分给这个 Group 多少股权" | 一条 SQL 查 `tenant_ownership` 表，`WHERE tenant_id IN (companyTenantIds) AND owner_type='group' AND partner_tenant_id=groupTenantId`——**当月/未指定历史月份**用这条（live 表） |
| `findHistoricalGroupEquityPercentages(companyTenantIds, groupTenantId, effectiveMonth)` | 同上，历史月份快照 | 查 `tenant_ownership_history`，多一个 `effective_month=?` 条件——**非当月**用这条 |

`findGroupEquityPercentages`/`findHistoricalGroupEquityPercentages` **直接复用现成的 `TenantOwnership`/`TenantOwnershipHistory` 实体类**当 MyBatis 的 `resultType`，没有为这两条查询另外建 DTO——沿用第 1 节"DTO 尽量少建"的原则。

`DashboardKpiDTO.java` 内嵌的 `RoleAmount` 静态类新增一个字段：

```java
public static class RoleAmount {
    private String role;
    private BigDecimal amount;
    private Integer tenantId;  // 只有 *ByRoleAndTenant 这两条查询会填这个字段，其它查询用不到，留 null
}
```

`aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant` 的 Mapper SQL 是把 `aggregateWinLossByRole`/`aggregateCrDrByRole` 里原本的每一个 `UNION ALL` 分支**原样照抄**，只是每个 `SELECT` 多加一列 `t.tenant_id AS tenantId`，最外层 `GROUP BY` 从 `x.role` 改成 `x.tenantId, x.role`——WIN/LOSE/ADJUSTMENT、手动 PROFIT 转账、RATE 中间人手续费两种格式（单边行 + 旧版两边行）这几个分支，判断条件一个字都没改。

### 10.4 后端：Service

`DashboardService.java` 新增：

```java
DashboardKpiDTO getKpiForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                LocalDate dateFrom, LocalDate dateTo, String currencyCode);
```

`DashboardServiceImpl.java` 里的实现拆成几块：

- **`getKpiForGroup(...)`**：校验参数、确认 `groupTenantId` 对应的 tenant 确实是 `tenant_type=GROUP`（不是 COMPANY），然后跟 `getKpi()` 的结构完全对称——算当前区间的 Group KPI，再用第 7 节现成的 `resolvePreviousRange()` 算上一期区间，把当前和上一期各算一遍，`previousDateFrom`/`previousDateTo`/`previous*` 字段全部照填（**Group KPI 是有"较上一期"对比的**，跟第 9 节 Company: All 不算这个不一样——因为 Group KPI 走的是单公司同款的完整流程，不是"All"那种故意简化的汇总）。
- **`computeGroupKpi(groupTenantId, companyTenantIds, dateFrom, dateTo, currency)`**：私有方法，把"Group Profit 加权汇总"和"Group Expenses"拼成一组数字：
  ```
  groupProfit  = computeGroupProfit(...)                                    // 见下面
  groupExpenses = computeProfitExpenses(List.of(groupTenantId), ...).expenses // 直接复用单公司逻辑，只取 expenses 这一项
  groupNetProfit = groupProfit.add(groupExpenses)
  ```
  这里特意**只取** `computeProfitExpenses()` 返回结果里的 `.expenses`，**不取** `.profit`——因为哪怕 Group 自己的账本里意外出现了 role=PROFIT 的流水，那也不算数，Group Profit 只能来自子公司加权汇总这一条路径，这是业务规则本身决定的，不是漏取。
- **`computeGroupProfit(companyTenantIds, groupTenantId, dateFrom, dateTo, currency)`**：私有方法，对应 10.2 里说的"2 条 SQL + Java 加权求和"：
  1. `aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant` 各查一次，结果 nest 成 `Map<tenantId, Map<role, amount>>`
  2. `findGroupEquityPercentages`（当月）或 `findHistoricalGroupEquityPercentages`（非当月，判断规则跟 `findOwnershipPercentage()` 一样用 `YearMonth.from(dateTo)` 是否等于当前月）查出 `Map<tenantId, percentage>`
  3. 遍历 `companyTenantIds`：查不到百分比或百分比是 0 的公司直接 `continue`（贡献 0，不用算）；否则 `companyNetProfit = companyProfit.add(companyExpenses)`，再 `groupProfit = groupProfit.add(companyNetProfit × percentage / 100)`（`scale=8`，跟 Earnings 那个 `earningsFrom()` 用同一个精度约定）
  4. `companyTenantIds` 为空（前端没传或这个 Group 确实没有子公司）直接返回 0，不发 SQL
- **Group Earnings**：**完全没有新写代码**——直接调用现成的 `applyEarnings(dto, groupTenantId, dateTo, groupNetProfit)` / `resolveEarningsAmount(groupTenantId, previousDateTo, previousGroupNetProfit)`，只是把原来传"公司的 tenantId"这个参数换成"Group 自己的 tenantId"。这两个方法内部本来就是查 `tenant_ownership`/`tenant_ownership_history` 表 `tenant_id=?` 这一行，`tenant_id` 传公司 id 还是 Group id，对这两个方法来说没有任何区别——这正是 10.1 里说的"Group Earnings 对应的就是 `tenant_ownership` 表里 `tenant_id`=Group 自己 id 的那一行"，数据结构层面天然支持，不需要额外分支。

### 10.5 后端：Controller

```
GET /api/dashboard/group-kpi?group_tenant_id=&company_tenant_ids=1,2,3&date_from=&date_to=&currency=
```

| 参数 | 说明 |
|---|---|
| `group_tenant_id` | Group 自己的 tenant id（数字或 "AP" 这种 code，走跟 `/kpi` 一样的 `resolveTenantId()` 解析） |
| `company_tenant_ids` | 逗号分隔的子公司 tenant id 列表，**可以不传或传空字符串**——这时候当作这个 Group 没有子公司，Group Profit 直接是 0，不报错 |
| `date_from`/`date_to`/`currency` | 跟其它接口一样 |

响应体形状跟 `GET /api/dashboard/kpi` **完全一样**（`profit`/`expenses`/`netProfit`/`showEarnings`/`earningsPercentage`/`earnings`/`previous*`），因为背后用的就是同一个 `DashboardKpiDTO`。

**这次实现过程中有一段小插曲**：写 Service 层时，`DashboardController.java` 磁盘上已经存在一个更早遗留、从没编译通过的 `/group-kpi` 端点（4 个参数的旧版本，没有 `company_tenant_ids`，调用的 Service 方法签名跟新写的对不上）。按"改动看起来不对就先问，不要自己悄悄改掉"的原则，先跟用户确认了处理方式，最后由用户自己把这段代码手动合并成了现在这一个 5 参数、路径叫 `/group-kpi` 的正确版本（我这边原本临时用的路径名是 `/kpi-group`，最终以用户合并后落地在磁盘上的 `/group-kpi` 为准）。

### 10.6 前端

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：

- **范围判断** `groupKpiScope`：`selectedGroup` 非空 + 不是 `groupAllMode`（那是第 9 节 Company: All）+ (`usesGroupLedgerDashboard` 或 `groupOnlyDashboard`，这两个都是项目里现成的"正在看 Group 自己账本"判断)。**明确排除** `groupsAllGroupLevel`（同时看 AP+IG 所有 Group 合并、`Group ID: All` 那种）——这种场景没有单一的 Group tenant id，这次的 `/group-kpi` 接口管不到，继续显示为空。
- **`groupKpiTenantId`**：从 `companies` 数组里找到 `company_id`/`code` 等于当前 Group 代码本身的那一行（用项目里现成的 `companyRowIsGroupEntity()` 判断——Group 在公司列表数据结构里本来就是自己一行），取它的 `.id`。
- **`groupKpiCompanyTenantIds`**：**不能**直接复用第 9 节的 `resolveMergeCompanyList()`——那背后带着 Company: All 专用的 `allowC168: false` 排除规则，会把 C168 排除掉（真实踩过一次，见第 5 节 Bug 6）。现在改成用 `companiesForCompanyPicker(companies, selectedGroup, groupIds)`（Dashboard 页面 "Company" 选择器 chip 本身在用的函数，不带这条排除规则）+ `filterCompaniesForDashboardApiAccess()` 权限过滤。
- **KPI 请求**：新增 `springKpiGroupData`/`springKpiGroupLoading` state + 一个新的 `useEffect`，打 `GET api/dashboard/group-kpi`，跟其它 Spring 接口一样的"一次 GET 拿全部数字"模式。
- **代码复用小改动**：因为 `/kpi` 和 `/group-kpi` 返回的数据形状完全一样，把 `kpi` useMemo 里原本内联写的那段"从 `previousProfit`/`previousExpenses`/`previousNetProfit`/`previousEarnings` 建 `comparisons`"逻辑抽成了一个模块级函数 `buildKpiFromSpringPayload(payload)`，单公司分支和 Group 分支现在共用这一个函数，不是复制一份改改字段名。

### 10.7 尚未覆盖 / 未验证

- **`groupsAllGroupLevel`**（`Group ID: All`，同时合并 AP+IG 两个 Group 一起看）——没有单一 Group tenant id，这次的接口设计管不到，继续保持"没有 Spring 后端"的空状态，需要的话得另外设计（比如把两个 Group 的 KPI 结果在前端或后端再加总一次）
- Bug 5（Group 账本 Currency 选择器）修复本身也还没有真机验证，见第 5 节 Bug 5 结尾
- 之前用真实数据核对时发现一个**跟 Dashboard 无关、但会干扰验证结果**的数据问题：Ownership 页面今天保存时，C168 的 live `tenant_ownership` 表把"K（BOSS）90%"这一行弄丢了、只剩"Group: AP 10%"那一行（历史上 8 月的快照两行都在，9 月的快照和 live 表都只剩 1 行）——这是 Ownership 保存那边的问题，不是这次 Group KPI 代码的问题，但会影响"当前月 K 在 C168 的 Earnings 还能不能查到 90%"，如果后续验证发现 Earnings 相关数字对不上，先去确认 Ownership 页面的配置是不是又被覆盖了，不要先怀疑 Dashboard 这边的代码

**已经真机验证过的部分**：`groupKpiCompanyTenantIds` 的修复（第 5 节 Bug 6）——用户重新刷新 AP 页面、选了 8/1~9/9 这个真的有流水的区间，Profit 显示 −2,096.95（不再是 0），跟"子公司 NetProfit × 股权%"这套算法算出来的方向和量级吻合，Bug 6 确认修好。

### 10.8 Group Trend Chart（后续补的，最初一版明确说"chart 部分后续再做"）

> Group Trend Chart 走的**跟 10.1 节 Group KPI 完全同一套算法**，只是从"整个区间一个总数"变成"每一天一个数"：
> Group Profit(某天) = Σ(每家子公司**那一天**自己的 NetProfit × 该公司**那个月**分给这个 Group 的股权%)，
> Group Expenses(某天) = Group 自己账本**那一天**的数字，Group NetProfit(某天) = 二者相加。

**跟用户确认过的关键设计点**：股权百分比这次**按月精确查**，不是像"Company: All"或者最初设想的那样用查询区间末尾一天的百分比顶到底——哪个月股权配置变过，那个月的数字就用那个月自己的百分比；某个月压根没配置过，就当 0% 处理（这个决定也顺带把 8.1.1 节里 Company 模式 Trend Chart 的 Earnings 线一起改成了同一套按月算法，两边不再是两套不一致的简化）。

**性能确认**（用户当面问过"按月算会不会拖慢响应、增加请求数"）：
- **前端请求数不变**，还是 1 次 `GET /api/dashboard/chart-group`，按月还是按区间末尾算百分比，前端完全感知不到区别，这个决定只影响后端内部怎么查。
- **后端 SQL 数量固定**，不会随区间拉长或月份变多而线性增加——历史月份的百分比用一条 `effective_month IN (...)` **批量**查完（不是一个月发一条 SQL），当前月再单独一条 `live` 查询，撑死是"1 条批量历史查询 + 1 条 live 查询"。

**Dao / Mapper 新增**（`DashboardDao.java` + `DashboardMapper.xml`）：

| 方法 | 用途 |
|---|---|
| `aggregateWinLossByRoleAndTenantAndDate` / `aggregateCrDrByRoleAndTenantAndDate` | 跟 `aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant`（第 10.3 节）完全同一套规则，再多按 `transaction_date` 分一层组——知道"每家子公司每一天自己赚了多少"，喂给 Group Profit 那条线 |
| `findOwnershipPercentagesByMonths(tenantId, accountId, ownerType, effectiveMonths)` | Company 和 Group 的 Earnings 走势线**共用**：一个身份、一个 tenant（公司或 Group 都行），一条 `effective_month IN (...)` 批量查一批历史月份的股权%（8.1.1 节详细写了） |
| `findGroupEquityPercentagesByMonths(companyTenantIds, groupTenantId, effectiveMonths)` | Group Profit 走势线专用：一批子公司、一批历史月份，一条 `IN (...)` 查完 |

`DashboardTrendPointDTO.RoleAmount` 也加了 `tenantId` 字段（只有上面两条新查询会填，其它查询留 null），跟第 10.3 节 `DashboardKpiDTO.RoleAmount` 加 `tenantId` 是同一个做法。

**Service 新增**（`DashboardServiceImpl.java`）：
- `getTrendForGroup(groupTenantId, companyTenantIds, dateFrom, dateTo, currencyCode)`：校验参数、确认 `tenant_type=GROUP`，调用 `buildGroupTrendPoints()` 算出 Profit/Expenses/NetProfit 三条线，`resolveOwnerType()` 不是 `null` 才补 Earnings 那条线（复用 8.1.1 节新增的 `resolveOwnershipPercentagesByMonth`/`applyTrendEarnings`，`tenantId` 传 Group 自己的 id）
- `buildGroupTrendPoints()`：Group 自己账本每天的 Expenses 复用现成的 `aggregateWinLossByRoleAndDate`/`aggregateCrDrByRoleAndDate`（`tenantIds=[groupTenantId]`，不用新写）；子公司每天的 Win/Loss+Cr/Dr 用新查询；股权百分比用新增的私有方法 `resolveGroupEquityPercentagesByMonth()`（跟 `resolveOwnershipPercentagesByMonth()` 是同一个"当前月 live、其余月份批量查历史"模式，只是从"一个身份"换成"一批子公司"）算出 `Map<YearMonth, Map<公司id, 百分比>>`；每一天：查这天所在月份的百分比表，跟这天每家子公司的 NetProfit 相乘、加总成 Group Profit，跟 KPI 卡片的 `computeGroupProfit()` 逻辑完全对应，只是多了"这天属于哪个月"这一步查表

**Controller 新增**：
```
GET /api/dashboard/chart-group?group_tenant_id=&company_tenant_ids=&date_from=&date_to=&currency=
```
参数、错误处理跟 `/group-kpi` 完全一样，响应体是 `List<DashboardTrendPointDTO>`（每个点带 `earnings`）。

**前端**（`useDashboardPage.js` + `dashboardChart.jsx`）：
- 新增 `springTrendGroupData`/`springTrendGroupLoading` state + `useEffect` 打 `chart-group`，复用现成的 `groupKpiTenantId`/`groupKpiCompanyTenantIds`（跟 `/group-kpi` 一模一样的参数）
- `chartRows` useMemo 加了 `groupKpiScope` 分支，跟单公司、Company: All 用**同一个** `buildSpringTrendChartRows()`
- `buildSpringTrendChartRows()` 这次顺带做了个简化：不再接 `earningsMultiplier` 参数，直接读每个点的 `earnings` 字段（8.1.1 节详细写了），Company/Company:All/Group 三种场景现在完全共用同一份构建逻辑，没有为 Group 另外写一份

**这次没做/没验证的部分**：
- 只做到前后端编译通过（`mvn compile` + `vite build`），**没有真机打开 Group 页面切到 Trend Chart 肉眼确认走势线数字是否正确**——KPI 卡片那部分已经真机验证过了（见上面 10.7），但 Trend Chart 这次没有单独再测一遍
- 按月精确算股权百分比这个逻辑本身，没有拿"股权比例中途真的变过"的真实场景测过（同 8.1.1 节的未验证事项）
- `groupsAllGroupLevel`（`Group ID: All`）的 Trend Chart 跟 KPI 卡片一样，这次没有覆盖到，继续显示为空
