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
> **最后更新**：2026-09-10（新增第 12～19 节：汇率同步定时任务 + 换算引擎、单公司 Currency/Earning
> Tab 按币种拆分、Group Net Profit Tab（按公司拆分）、Group Currency Tab（按币种拆分，Group 加权版）、
> Company: All Currency Tab（按币种拆分，纯求和版）、Company: All Earnings KPI 卡片 + Trend Chart
> Earnings 线（批量降级链路，公司数量/月份跨度都不会增加查询次数）、Company: All Currency Tab 的
> Earning 列（第 19 节，补上了第 16/17 节留下的缺口）。第 11 节的降级链路记录：Company
> Earnings 卡片 + Trend Chart 走势线的"直接持股 or 借道 Group"降级链路——公司自己没有直接持股配置时，
> 改成查它分给了哪个 Group、再查登录身份在那个 Group 里的持股%，两个百分比相乘得出有效持股率；KPI
> 卡片部分已真机验证数字对了，Trend Chart 部分还没有真机验证）

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
11. [Company Earnings 降级链路：直接持股 or 借道 Group](#11-company-earnings-降级链路直接持股-or-借道-group)
12. [Exchange Rate 汇率系统：定时任务 + 换算引擎](#12-exchange-rate-汇率系统定时任务--换算引擎)
13. [单公司 Currency / Earning Tab 按币种拆分](#13-单公司-currency--earning-tab-按币种拆分)
14. [Group Net Profit Tab：按旗下公司拆分](#14-group-net-profit-tab按旗下公司拆分)
15. [Group Currency Tab：按币种拆分（Group 加权版）](#15-group-currency-tab按币种拆分group-加权版)
16. [Company: All Currency Tab：按币种拆分（纯求和版）](#16-company-all-currency-tab按币种拆分纯求和版)
17. [Company: All Earnings：批量降级链路](#17-company-all-earnings批量降级链路)
18. [Company: All Trend Chart Earnings 线：公司 × 月份双批量](#18-company-all-trend-chart-earnings-线公司--月份双批量)
19. [Company: All Currency Tab 的 Earning 列：补上最后一块拼图](#19-company-all-currency-tab-的-earning-列补上最后一块拼图)

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
- ~~Earnings 按币种拆分的圆环图 + Currency/Amount/Original Amount/Rate 明细列表~~——**已做**：单公司 Currency/Earning Tab 见第 13 节，Group Currency Tab 见第 15 节
- ~~FX 汇率换算（`frankfurterRates.js`）~~——**已做**：后端 `exchange_rate` 表 + `ExchangeRateService`，见第 12 节；前端仍保留 `frankfurterRates.js` 作为 Rate 列的兜底（后端没有该币种汇率时才用），见 13.4
- "Company: All" 场景（第 9 节）下的 Currency/Earning Tab 按币种拆分——只做了单公司（第 13 节）和 Group（第 15 节）两种 scope，`getKpiForCompanies()` 场景的按币种拆分没有做
- Group 级别的股权链路（`tenant_ownership.owner_type='group'`、多层集团路径连乘）——`DashboardServiceImpl` 目前只处理 `owner`/`user` 两种直接持股，`group` 那条链路完全没接
- "Company: All" 场景下的 Earnings 卡片、"较上一期"对比——`getKpiForCompanies()`/`getTrendForCompanies()` 都没有算这两样（不是漏做，是这次明确商量好不做，见第 9.1 节）

以下部分**代码已经写了，但这次没有拿真实数据交叉验证过**，如果之后发现数字不对可以从这里查起：

- 历史月份股权快照（`tenant_ownership_history`）的读取路径——`applyEarnings()`/`resolveEarningsAmount()` 里 `YearMonth.now()` 判断分支，这次验证的都是当月数据
- ~~Earnings 卡片本身的乘数计算（`percentage`、`showEarnings` 判断）——这次验证重点在 Profit/Expenses/Net Profit，没有拿一个真实配了股权比例的账号登录测过 Earnings 卡片~~——**已确认算法正确**（用户验证，见第 11/17/18 节后续多轮真机 + 真实数据交叉验证）
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
- ~~**Earnings 卡片**、**"较上一期"对比**：`getKpiForCompanies()`/`getTrendForCompanies()` 都没有算，是这次明确商量好先不做的，不是漏了~~——**已改主意，Earnings 卡片补上了**，见第 17 节；~~Trend Chart 的 Earnings 线、Currency Tab 的 Earning 列这两样还没做~~——**都已补上**，Trend Chart 见第 18 节，Currency Tab 的 Earning 列见第 19 节；"较上一期"对比仍然没做（Currency Tab 的 Currency 列本身已经在第 16 节做了）
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

---

## 11. Company Earnings 降级链路：直接持股 or 借道 Group

> 范围：只动 **Company 视角的 Earnings**（KPI 卡片 + Trend Chart 走势线），Group 视角自己的 Earnings
> （第 10 节）完全不受影响、不会触发这套降级。这次没有改后端接口的参数或响应体形状，`Controller` 和
> 前端都不用动——纯粹是 `DashboardServiceImpl` 内部"这个百分比要去哪查"的判断逻辑升级。

### 11.1 需求背景

用户发现一个真实场景：C168 这家公司自己在 Ownership 页面的 "Account Ownership" 标签页**没有配置任何直接持股**，但它把 10% 的股权分给了 AP 这个 Group（`tenant_ownership` 表里 `tenant_id=C168、owner_type='group'、partner_tenant_id=AP` 那一行）；同时登录身份 K 在 AP 这个 Group 自己身上（"Group Earnings" 标签页）配置了持股。

**在这次改动之前**，C168 的 Earnings 卡片只查 C168 自己身上有没有 `owner_type='owner'/'user'` 的直接持股行——查不到就直接不显示卡片，即使 K 实际上通过"C168 → AP → K"这条链路间接持有 C168 的一部分收益。

**用户要的效果**：C168 的 Earnings 卡片应该按这个公式算出来并展示：
```
C168 Earnings = C168 NetProfit × (C168 分给 AP 的%) × (K 在 AP 里的持股%)
```
这条链路是**降级路径**，优先级低于直接持股——如果 C168 自己本来就配了 K 的直接持股，就直接用那个数字，完全不看 Group 这条路；只有直接持股查不到的时候，才尝试走 Group 这条路；两条路都查不到，才完全不显示 Earnings 卡片。用户确认过：**一家公司不会同时分股权给两个不同的 Group**，所以降级路径永远最多涉及一个 Group，不用处理"多个 Group 加权平均"这种情况。

### 11.2 后端：Dao / Mapper 新增

新增 3 条查询，全部复用现成的 `TenantOwnership`/`TenantOwnershipHistory` 实体，没有建新 DTO：

| 方法 | 用途 |
|---|---|
| `findCompanyGroupAllocation(tenantId)` | KPI 卡片用：查这家公司自己名下 `owner_type='group'` 的那一行（**当月**，live 表，`LIMIT 1`——一家公司最多分给一个 Group，用户已确认） |
| `findHistoricalCompanyGroupAllocation(tenantId, effectiveMonth)` | 上面那条的历史快照版本（`tenant_ownership_history`，指定月份，`LIMIT 1`） |
| `findCompanyGroupAllocationsByMonths(tenantId, effectiveMonths)` | Trend Chart 用：批量版，一条 `effective_month IN (...)` 查出这家公司在**一批历史月份**里各自的 Group 分配行，不按月循环查询 |

这三条本质上是 `findGroupEquityPercentages`/`findHistoricalGroupEquityPercentages`/`findGroupEquityPercentagesByMonths`（第 10.3 节）的**反方向查询**——那三条是"已知 Group，查一批公司分了多少给它"，这三条是"已知一家公司，查它分给了哪个 Group、分了多少"（`groupTenantId` 反而是查出来的结果之一，不是查询条件）。

"K 在 AP 里的持股%"这一步**不需要新查询**——直接复用已经有的 `findOwnershipPercentage()`（KPI 用）/`findOwnershipPercentagesByMonths`（Trend 用），只是把参数从"公司自己的 tenantId"换成"查出来的 Group 的 tenantId"，这两个方法本来就是通用的，不关心 tenant 是公司还是 Group。

### 11.3 后端：Service（KPI 卡片部分）

`applyEarnings()`/`resolveEarningsAmount()` 都新增一个 `allowGroupCascade: boolean` 参数，内部不再直接调 `findOwnershipPercentage()`，改成调新增的：

```
resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, allowGroupCascade):
  1. direct = findOwnershipPercentage(tenantId, dateTo, ownerType)
  2. direct 查到且 > 0 → 直接返回 direct（原有逻辑一个字没改，优先级最高）
  3. allowGroupCascade = false → 返回 null（不降级）
  4. 查这家公司的 Group 分配行 findCompanyGroupAllocation(tenantId, dateTo)
     → 查不到 / 百分比 ≤ 0 → 返回 null
  5. 用查到的 groupTenantId 再查一次 findOwnershipPercentage(groupTenantId, dateTo, ownerType)
     → 查不到 / ≤ 0 → 返回 null
  6. 两个百分比相乘 ÷ 100（scale=8，跟 earningsFrom() 同一个精度约定）→ 作为"有效持股率"返回
```

调用方：
- **`getKpi()`**（Company 视角）两处调用（当前区间 + 上一期）都传 `allowGroupCascade=true`——允许降级。
- **`getKpiForGroup()`**（Group 视角）两处调用都传 `false`——Group 自己的 Earnings 只查 `tenant_id=Group自己id` 那一行，不会再往上一层去找"这个 Group 有没有分给另一个 Group"（目前数据结构也没有这种嵌套关系，传 `false` 是为了以后万一出现类似结构时不会被误触发）。

`dto.earningsPercentage` 展示的就是这个"有效持股率"（比如 10% × 70% = 7%），前端卡片本来就只显示 `earnings` 这个金额，没有单独把百分比数字显示出来，所以不存在"标签写的是直接持股%、其实是换算出来的%"这种文案对不上的问题。

**验证**：用户拿真实数据测过，C168（没有直接持股）+ K（在 AP 里有持股）这个组合，Earnings 卡片正确显示出来了，数字对上——**这部分已经真机验证过，不是只编译通过**。

### 11.4 后端：Service（Trend Chart 走势线部分）

Trend Chart 的 Earnings 线本来就是"按月算"的（第 8.1.1 节），所以这次要做的是把 11.3 的两级判断按月重新实现一遍，而不是简单调用一次：

```
resolveEffectiveEarningsPercentagesByMonth(tenantId, accountId, ownerType, dateFrom, dateTo, allowGroupCascade):
  1. directByMonth = resolveOwnershipPercentagesByMonth(...)   // 第 8.1.1 节现成的方法，原样复用
  2. allowGroupCascade = false → 直接返回 directByMonth
  3. allocationByMonth = resolveCompanyGroupAllocationsByMonth(tenantId, dateFrom, dateTo)
     // 新增：跟 resolveOwnershipPercentagesByMonth 同一个"当前月 live + 其余月份批量历史查询"模式，
     // 只是查的是"公司自己的 Group 分配行"，不是"身份的持股行"
  4. allocationByMonth 是空的（这几个月这家公司压根没分给任何 Group）→ 直接返回 directByMonth
  5. 把 allocationByMonth 里出现过的 Group（去重，通常就一个）各自批量查一次
     resolveOwnershipPercentagesByMonth(该Group的id, accountId, ownerType, dateFrom, dateTo)
     → 一个 Map<groupTenantId, Map<月份, 百分比>>
  6. 遍历每个月：这个月直接持股已经 > 0 → 跳过，保留 directByMonth 的值（直接持股优先级更高）；
     否则查这个月的 Group 分配% 和对应 Group 该月的持股%，两个都 > 0 才补进结果，缺一个就跳过
     （跳过 = 那个月留空，`applyTrendEarnings` 会把它当 0% 处理，这个规则第 8.1.1 节已经定了）
```

调用方：`getTrend()`（Company）传 `allowGroupCascade=true`；`getTrendForGroup()` 传 `false`（等价于之前的行为，只是统一走同一套代码，不用维护两份逻辑）。

**性能确认**：不管区间横跨多少个月，"公司的 Group 分配"这一步固定是"1 条批量历史查询 + 1 次 live 查询"；"登录身份在 Group 里的持股"这一步是**按去重后的 Group 数量**各来一组"批量历史 + live"查询——用户已经确认一家公司最多分给一个 Group，所以实际最多只会多这一组（2 条）查询，不会随时间跨度或 Group 数量线性增长。前端请求数完全不变，还是 1 次 `GET /api/dashboard/chart`。

### 11.5 尚未覆盖 / 未验证

- ~~Trend Chart 这部分只做到后端编译通过，没有真机打开走势线肉眼确认 Earnings 那条线在"直接持股查不到、走 Group 降级"的月份数字是否正确~~——**已真机验证，数字对了**（用户确认，具体是哪个 Group/月份没有单独记录细节）
- ~~`getKpiCurrencyBreakdown()`（按币种拆分的 Earnings 面板）没有接上这套降级逻辑~~——**已补上**：改成调 `resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, true)`，跟 `getKpi()` 同一个方法、同一个 `allowGroupCascade=true`，只查一次（不是每个币种查一次），套到每个币种的净利润上。只影响 Earning Tab 的 `earnings`/`earningsConverted` 两个字段，Currency Tab 的 `netProfit`/`amount`/`rate` 不受影响。这次只做到后端编译通过，没有真机验证过借道 Group 场景下这个面板的数字。
- 一家公司同时分股权给两个不同 Group 这种情况没有处理（用户已确认这不会发生，`findCompanyGroupAllocation`/`findCompanyGroupAllocationsByMonths` 都是 `LIMIT 1`/取查到的第一批，如果数据库里意外出现多行，行为是"随便挑一行"而不是报错或加权平均）
- `groupsAllGroupLevel`（`Group ID: All`）的 Trend Chart 跟 KPI 卡片一样，这次没有覆盖到，继续显示为空

---

## 12. Exchange Rate 汇率系统：定时任务 + 换算引擎

> 背景：旧版汇率功能全部靠前端直连 Frankfurter（`frankfurterRates.js`），有两个问题——一是稳定币
> （USDT/USDC）Frankfurter 根本没有报价，永远查不到；二是切换 base currency 要么现查、要么维护一份
> NxN 汇率矩阵。这次的做法是**后端每天批量拉一次、存本地表、锚定单一币种（USD）反推任意两币种汇率**，
> dashboard 请求时只读本地表，不再有外部网络调用。

### 12.1 `exchange_rate` 表

```sql
CREATE TABLE exchange_rate (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    currency_code VARCHAR(10) NOT NULL,   -- 如 MYR、USD、USDT
    rate_to_usd DECIMAL(18,8) NOT NULL,   -- 1 单位该币种 = ? USD；USD 自己 = 1
    rate_date DATE NOT NULL,              -- 这条快照对应哪一天
    source VARCHAR(20) NOT NULL DEFAULT 'frankfurter',  -- frankfurter | stablecoin | manual
    created_at TIMESTAMP, updated_at TIMESTAMP,
    UNIQUE KEY uq_exchange_rate_code_date (currency_code, rate_date)
);
```

迁移脚本：`backend/src/main/resources/sql/migrate_add_exchange_rate_table.sql`（`CREATE TABLE IF NOT EXISTS`，可重复执行）。全局表，不分 tenant——汇率不是租户级数据。

**为什么锚定单一币种**：只存"每个币种对 USD"这一份，不用维护 NxN 矩阵；任意两币种 A→B 的汇率现算 `rate(A→USD) / rate(B→USD)`，纯算术，不用额外查询。

### 12.2 `ExchangeRateSyncJob`：每天一次的定时任务

`backend/src/main/java/com/eazycount/cron/ExchangeRateSyncJob.java`，`@Scheduled(cron = "${app.exchange-rate.cron}")`，默认 `0 5 0 * * *`（每天 00:05）——汇率数据源本身一天只更新一次，没必要按小时轮询。

流程：
1. 查 `currency` 表所有 tenant 用到的 ACTIVE 币种（`ExchangeRateDao#findDistinctActiveCurrencyCodes`），不写死清单。
2. 拆成两类：稳定币（`USDT`、`USDC`，写死在 `STABLECOINS` 常量里）直接写 `rate_to_usd=1.0`，不发请求；其余法币一次性批量调 Frankfurter。
3. 批量请求返回的响应里缺失的币种（Frankfurter 不认识的代码）**只记日志、跳过，不逐个重试**——这正是旧系统"per-currency查询变慢"的问题在这里被规避的地方。
4. `INSERT ... ON DUPLICATE KEY UPDATE`（利用 `(currency_code, rate_date)` 唯一索引），当天重复跑不会出错。

**踩过的坑：Frankfurter API 版本**——最初配的是 `https://api.frankfurter.dev/v2/latest?base=USD&symbols=...`，实测**整个请求直接 404**（v2 的 endpoint 其实改成了 `/v2/rates` + `quotes` 参数，不是 `/latest` + `symbols`）。改用 `v1`（官方标注 "frozen" 即长期稳定，不是要废弃）：`https://api.frankfurter.dev/v1/latest?base=USD&symbols=...`，参数形状跟原本写的代码完全匹配。已在 `application.yml` 的 `app.exchange-rate.frankfurter-url` 里改正。

**真实验证**：`count_real` 库里的 `currency` 表混了几个非标准代码（`RM`、`THE`、`NPR`、`PGK`），v1 批量请求遇到不认识的代码会**自动从返回结果里剔除，不会让整个请求 404**——手动跑过一次任务，`exchange_rate` 表正确写入了 `AUD/CAD/CNY/EUR/HKD/IDR/MYR/SGD/THB/USD/USDT`（`USDT` 是 `source='stablecoin'`），`RM/THE/NPR/PGK` 被跳过、日志里能看到警告，任务本身没有报错、没有卡住。

### 12.3 `ExchangeRateService`：换算引擎

`backend/src/main/java/com/eazycount/service/ExchangeRateService.java` / `impl/ExchangeRateServiceImpl.java`：

```java
Map<String, BigDecimal> loadRatesToUsd();  // 一次 DB 读取，返回 code -> rate_to_usd
BigDecimal convert(BigDecimal amount, String fromCode, String toCode, Map<String, BigDecimal> ratesToUsd);
```

`convert()` 是纯算术：`amount × rate(from→USD) ÷ rate(to→USD)`，任一币种没有汇率就返回 `null`（调用方渲染成 "—"）。调用方（`DashboardServiceImpl`）在一次请求里只调 `loadRatesToUsd()` 一次，之后在内存里循环调用 `convert()`，不会有 per-row 的额外查询。

`ExchangeRateDao#findLatestRates()`（`backend/src/main/resources/mybatis/ExchangeRateMapper.xml`）取的是**每个币种各自最近一次成功的快照**（`rate_date <= CURDATE()` 里的 `MAX`，按 `currency_code` 分别取），不是"全局最新的那一天"——如果某天同步任务只有部分币种失败，那些币种会自动落到上一次成功的日期，不会因为"今天没抓到"就整体查不到。

### 12.4 尚未覆盖 / 未验证

- 没有做"手动触发一次同步"的管理端入口，目前只能等每天 00:05 的定时任务，或者临时写一个 test 手动调 `ExchangeRateSyncJob#syncDailyRates()`（这次验证就是这么做的，验证完把 test 文件删了）
- 没有对 Frankfurter 请求失败做告警/通知，失败了只是记一条 `WARN` 日志

---

## 13. 单公司 Currency / Earning Tab 按币种拆分

> 范围：Dashboard 页面右侧那张"Currency 分布"卡片（截图里 donut chart + Currency/Amount/Original
> Amount/Rate 表格），单一 COMPANY 类型租户。对应截图里 `Currency` 和 `Earning` 两个 tab。

### 13.1 后端：`getKpiCurrencyBreakdown()`

```
GET /api/dashboard/kpi/currency-breakdown?tenant_id=&date_from=&date_to=&base_currency=
```

新增 `DashboardCurrencyAmountDTO`：

```java
class DashboardCurrencyAmountDTO {
    String code;                    // 币种代码
    BigDecimal originalAmount;      // Currency tab: 该币种自己的 Net Profit（未换算）
    BigDecimal amount;              // originalAmount 换算成 base_currency 后的金额
    BigDecimal rate;                // 单位汇率：1 该币种 = ? base_currency
    BigDecimal earnings;            // Earning tab: 该币种 NetProfit × 当前登录身份的有效持股% (0，不是 null)
    BigDecimal earningsConverted;   // earnings 换算成 base_currency 后的金额
}
```

**新增 DAO 查询**（`DashboardDao#aggregateWinLossByRoleAndCurrency` / `aggregateCrDrByRoleAndCurrency`）：跟第 0 节的 `aggregateWinLossByRole`/`aggregateCrDrByRole` 业务规则完全一样，只是去掉 `currencyCode` 过滤、改成按 `currency` 分组返回——**一条 SQL 拿到该 tenant 名下每个币种各自的数字**，不用为每个币种单独查一次（这正是旧系统"按币种循环查询"变慢的原因）。

**计算逻辑**：
1. 币种清单 = 该 tenant 在 Currency Setting 里配置的**全部** ACTIVE 币种 ∪ 有交易活动的币种 ∪ 当前请求的 `base_currency`——不是只列有数据的币种，没有活动的币种也要出现在表格里显示 "—"。
2. 对每个币种：`hasActivity`（该币种在这个日期区间有任何 Win/Loss 或 Cr/Dr 记录）或者本身就是 `base_currency` → 算出真实的 `netProfit`（可能是 0）；否则 `netProfit = null`（渲染成 "—"，不是 0）。
3. `amount`/`rate` 用 `ExchangeRateService` 换算，一次请求只 `loadRatesToUsd()` 一次。
4. **Earning tab 的 `earnings`**：`resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, allowGroupCascade=true)`——跟 KPI 卡片 Earnings 数字**同一个方法**（第 11 节的降级链路：公司自己没有直接持股，就借道它分给的 Group 再乘一次持股%），**只查一次**（不是每个币种查一次），套到每个币种上：`earnings = (netProfit ?? 0) × percentage / 100`，没有活动或没有持股一律是 `0`（不是 `null`）——这是 Earning tab 跟 Currency tab 唯一的显示差异（"—" vs "0"）。

### 13.2 前端接入

`Count-frontend/src/pages/dashboard/hooks/useDashboardPage.js`：

- `springCurrencyBreakdownData`（新 state）：单一、简单的 `useEffect`（照抄 `springKpiData` 的写法，没有缓存/去重/预取机制），只在 `isSingleCompanyKpiScope && currencies.length > 1` 时才发请求。
- `earningsCurrencyRows`：单公司 scope 下直接读这份数据（`originalAmount`→`netProfit`、`amount`→`netProfitConverted`、`rate`、`earnings`/`earningsConverted`），**替换掉原本已经被禁用的 `dashboard_api.php` 链路**（`fetchDashboardApiHttpDeduped()` 之前一直是短路返回空数据的 no-op stub）。
- `useConvertedEarnings`/`allCurrencyEarningsReady`：单公司 scope 下的判断标准改成"批量响应是否已经落地"，不是"每一行是否都非 null"——因为没有活动的币种本来就该是合法的 `null`，不代表数据没查完。

### 13.3 过程中发现/修的 2 个 bug

**Bug A：Original Amount 列显示成了跟 Amount 一样的数字**——`dashboardEarnings.js` 的 `resolveEarningsRowDisplayAmounts()` 读的是 `row.earnings`（这个字段在 `mapPanelCurrencyRows()` 处理完之后，其实已经被覆盖成"展示用的金额"——可能是原生值也可能是换算值，取决于 `useConverted`），而不是专门保存原生值的 `row.originalEarnings`。这是前端已经存在的 bug（这部分功能之前一直是空数据，没暴露出来），改成读 `row.originalEarnings ?? row.earnings`。

**Bug B：稳定币（USDT）的 Rate 列永远显示 "—"**——Rate 列当时读的是前端自己直连 Frankfurter 拿到的 `exchangeRates.rates`，跟后端 `exchange_rate` 表是两条独立数据源；Frankfurter 不支持加密货币报价，USDT 永远查不到。新增 `resolveUnitRateLabel(row, code, baseCode, rates)`：**优先用这一行自己的 `row.rate`（后端算好的）**，只有没有才退回客户端 Frankfurter 汇率。

### 13.4 真实验证

用 tenant_id=2（公司 95）2026-09-07 的真实数据验证过：`MYR` 净利润 1,975.03、`CNY` 净利润 3.28（换算成 MYR 是 1.99）——跟截图里的数字完全对上，且 Original Amount 列显示的是 `3.28`（原生值），不是 `1.99`（换算值）。

### 13.5 尚未覆盖 / 未验证

- ~~"Company: All" 场景（第 9 节）没有做按币种拆分~~——**已做**，见第 16 节
- Rate 列目前是"双数据源"：单公司/Group scope 优先用后端 `exchange_rate` 表算出来的汇率，其它 scope（Company: All 等）还是走前端 Frankfurter——两边数值应该接近但不保证完全一致（拉取时间点不同），架构上还没完全统一
- **【待排查】NPR/PGK 的 Rate 列偶尔会显示一个具体数字，而不是预期的 "—"**——Frankfurter 官方支持的货币只有 30 种（`AUD BRL CAD CHF CNY CZK DKK EUR GBP HKD HUF IDR ILS INR ISK JPY KRW MXN MYR NOK NZD PHP PLN RON SEK SGD THB TRY USD ZAR`，用 `GET https://api.frankfurter.dev/v1/currencies` 查过），NPR/PGK 都不在里面，`exchange_rate` 表里也确认没有这两个币种的行——理论上不管后端还是前端直连 Frankfurter 都不可能查到真实汇率。用户反馈在 Company 模式下看到 NPR=0.026722、PGK=0.909008 这种具体数字（PGK 这个值在不同时间点的两张截图里完全相同，NPR 有细微差异），怀疑是 `resolveUnitRateLabel()`（`DashboardEarningsSummary.jsx`）在后端 `row.rate` 为 `null` 时退回的前端 `formatFrankfurterUnitRate()` 读到了浏览器 `sessionStorage`（`frankfurterRates.js` 的 `frankfurter_rates_v1:` 缓存前缀）里的旧脏数据，而不是真的查到了汇率——还没验证这个假设（没有清一下 sessionStorage 复现确认）。根治方案初步想法：单公司/Group 这两个 scope 既然后端已经是权威数据源，`row.rate` 为 `null` 就应该直接显示 "—"，不该再退回前端 Frankfurter——这次先记录，没有动代码。

---

## 14. Group Net Profit Tab：按旗下公司拆分

> 范围：Group-only 场景（单独看某个 Group、没选具体公司）下 Currency 卡片新增的第三个 tab——不是按
> 币种拆分，是按**这个 Group 旗下每家子公司自己的 Net Profit**拆分（截图里 AG/95/RS/VG/CX 那张表）。

### 14.1 后端：`getGroupCompanyNetProfitBreakdown()`

```
GET /api/dashboard/group-kpi/net-profit?group_tenant_id=&company_tenant_ids=&date_from=&date_to=&currency=
```

新增 `DashboardGroupCompanyNetProfitDTO { String code; BigDecimal netProfit; String group; }`。

**不新增业务逻辑 SQL**——`aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant` 这两条查询 `computeGroupProfit()`（Group Profit 加权汇总）本来就在跑，只是算完加权就把"每家公司自己的原始 Net Profit"这个中间结果丢掉了。这次把这个中间结果单独暴露成一个数组，**没有写新 SQL**。

单一货币、跟 `getKpiForGroup()` 的 `currency` 参数一样直接过滤，**不做 FX 换算**——跟 Group KPI 卡片本身用的是同一种"单币种直接过滤"的规则，比按币种拆分的第 15 节简单。

**新增**：`TenantDao#findTenantsByIds(List<Integer> tenantIds)`——批量查一批公司各自的 `code`，不是每家公司单独 `findTenantById` 一次（否则又是一次 N+1）。

### 14.2 前端接入 + Tab 出现条件

`springGroupCompanyBreakdownData`（新 state），只在 `groupOnlyDashboard`（单独看某个 Group、没选具体公司）**且**这个 Group 至少有一家子公司时才发请求。

`panelCurrencyRows` 的 `netProfitFor` 分支从原本读死掉的 `dashboardData?.subsidiary_earnings_by_company`（旧 PHP 时代字段，Spring 迁移后一直是空的）改成读这份新数据。`showNetProfitForTab` 这个 tab 显示开关本来就是 `groupOnlyDashboard && 有数据`——这次只是把数据源换掉，判断条件没有改。

### 14.3 真实验证

IG 这个 Group（tenant_id=33），2026 年 8 月，子公司 95/AG/RS/CX（tenant_id 2/5/3/6）：

| 公司 | Net Profit |
|---|---|
| AG | 183,029.94 |
| 95 | 26,220.36 |
| RS | 16,349.02 |
| CX | −5,400.00 |

跟截图里的数字完全对上。

### 14.4 尚未覆盖 / 未验证

- 没有做"较上一期"对比（这张表本身也没有这个概念，跟 Net Profit Tab 的定位一致——纯粹是当期各公司拆分）
- `VG` 这家公司在截图里显示 0.00，但它没有在 `tenant_ownership` 的 group 分配行里出现——`company_tenant_ids` 这个参数由前端算好传进来（`groupKpiCompanyTenantIds`），只要前端把它包含进列表，后端会正常查出它当期的 Net Profit（没有交易就是 0），不依赖它有没有配置股权分配

---

## 15. Group Currency Tab：按币种拆分（Group 加权版）

> 范围：Group-only（以及纯 Group 账本）场景下 Currency 卡片的 "Currency" tab——逻辑上跟第 13 节的
> 单公司版完全一样（Currency/Amount/Original Amount/Rate 四列），唯一区别是 Original Amount 的来源
> 不是某一家公司的 Net Profit，而是**这个 Group 自己算出来的 Net Profit**（`getKpiForGroup()` 那套
> "各子公司 Net Profit × 股权% 加权汇总"的结果）按币种拆开、每个币种各自的贡献。

### 15.1 核心难点：需要"tenant + currency"双维度分组的新查询

单公司版的 `aggregateWinLossByRoleAndCurrency`（按币种分组）和 Group Net Profit Tab 的 `aggregateWinLossByRoleAndTenant`（按公司分组）都不够用——这次需要的是"每家子公司在每个币种各自的 Win/Loss/Cr/Dr"，两个维度同时要。

**新增 DAO 查询**：`aggregateWinLossByRoleAndTenantAndCurrency` / `aggregateCrDrByRoleAndTenantAndCurrency`——业务规则（WIN/LOSE/ADJUSTMENT、手动 PROFIT 转账、RATE 中间人手续费两种历史格式、CLEAR 排除）跟现有的 `aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant` 完全一样，只是：去掉 `currencyCode` 过滤，`SELECT` 里加 `c.code AS currencyCode`，`GROUP BY x.tenantId, x.role` 变成 `GROUP BY x.tenantId, x.currencyCode, x.role`——一条 SQL 拿到 Group 下每家公司在每个币种各自的数字，不是循环查询。

### 15.2 计算公式

```
Group Net Profit(某币种) = Σ 每家子公司在该币种的 NetProfit × 该公司股权%
                          + Group 自己账本在该币种的 Expenses
```

跟 `computeGroupProfit()`（Group KPI 卡片单币种版）同一套权重规则，只是评估维度从"一个总数"变成"每个币种各自评估一次"；"Group 自己账本只算 Expenses"这条业务规则（第 10 节 `computeGroupKpi()` 已确认）在这里原样沿用，Group 自己账本的 Expenses 复用第 13 节已经写好的 `aggregateWinLossByRoleAndCurrency`/`aggregateCrDrByRoleAndCurrency`（`tenantIds` 传 Group 自己的 id）。

### 15.3 后端：`getGroupKpiCurrencyBreakdown()`

```
GET /api/dashboard/group-kpi/currency-breakdown?group_tenant_id=&company_tenant_ids=&date_from=&date_to=&base_currency=
```

**复用第 13 节的 `DashboardCurrencyAmountDTO`**（`code`/`originalAmount`/`amount`/`rate`/`earnings`/`earningsConverted`），不新建 DTO；换算引擎也复用同一个 `ExchangeRateService`。币种清单规则跟单公司版一致：Group 自己配置的币种 ∪ 有活动的币种（子公司或 Group 自己账本任一方有数据）∪ 当前 `base_currency`。`earnings`/`earningsConverted` 这两个字段的计算见 15.6。

### 15.4 前端接入

`springGroupCurrencyBreakdownData`（新 state），用的是 `groupKpiScope`（跟 Group KPI 卡片本身同一个 scope，比第 14 节 Net Profit Tab 的 `groupOnlyDashboard` **宽**——纯 Group 账本场景也适用，因为哪怕没有子公司，Group 自己账本的 Expenses 部分依然有意义）。`earningsCurrencyRows` 新增 Group 分支读这份数据；`useConvertedEarnings`/`allCurrencyEarningsReady` 同样加了 `groupKpiScope` 分支，判断标准是"响应是否落地"，跟单公司版原则一致。

### 15.5 真实验证 + 尚未覆盖

**已验证**：IG 这个 Group（tenant_id=33），2026 年 8 月，`base_currency=MYR` 时 `MYR` 行算出 **190,400.76**——跟本文档最开始讨论这个功能时给出的 Group 模式截图（"NET PROFIT · MYR 190,400.76"）完全一致。`AUD`/`CNY`/`EUR`/`SGD`/`USD` 都有对应的原生 + 换算金额；`NPR` 有原生金额但换算不了（Frankfurter 不支持，显示 "—"），跟单公司版行为一致。

**尚未覆盖**：
- 只做到后端真实数据验证 + 前端 `vite build` 编译通过，没有实机登录浏览器肉眼确认 Group Currency Tab 的表格渲染正常

### 15.6 Group Earning Tab：按币种拆分

`earnings`/`earningsConverted` 这两个字段接上了：跟 `getKpiForGroup()` 的 Earnings 卡片**同一个方法**
`resolveEffectiveEarningsPercentage(groupTenantId, dateTo, ownerType, allowGroupCascade=false)`——注意这里
`allowGroupCascade` 传的是 `false`，跟第 13 节 Company 版（传 `true`）唯一的区别：**Group 不会再往上借道
另一个 Group**（Group 本身已经是降级链路的终点）。只查一次（不是每个币种查一次），套用公式跟 Company
版一样：`earnings = (netProfit ?? 0) × percentage / 100`。

**"Group 自己没有直接持股，Earning Tab 应该整个不显示"这条规则不需要新代码**——`showEarningPanelTab`
前端本来就读 `kpi.showEarnings`，而 `kpi`（Group scope 下）就是 `/group-kpi` 返回的 `springKpiGroupData`，
这个 `showEarnings` 早就是用同一个 `allowGroupCascade=false` 算出来的（第 11.3 节）。也就是说"整个 Tab
显示与否"这件事在 KPI 卡片那条链路上已经解决了，这次只是把"每个币种具体多少钱"这个数字补上。

**已真机验证**：IG 这个 Group（tenant_id=33），`owner_type='owner', account_id=3, percentage=70%`，
用这个账号登录后 MYR 行显示 `133,280.53`（`190,400.76 × 70%`），用户实测确认数字对上——这是本文档
第 12～15 节里唯一一处走完"后端验证 + 前端 vite build + 真机登录肉眼确认"全流程的功能点。

---

## 16. Company: All Currency Tab：按币种拆分（纯求和版）

> 范围：同一 Group 标签下选 "Company: All"（截图里 `Group ID: IG` + `Company: All`）时 Currency 卡片的
> "Currency" tab——展示结构跟单公司版（第 13 节）一模一样，**Original Amount 的来源是"当前 Group 标签下
> 所有子公司在这个币种上的净利润直接相加"**，不像 Group Currency Tab（第 15 节）那样按股权% 加权。
> 跟现有 `getKpiForCompanies()`（Company: All 的 KPI 卡片）用的是同一条"纯求和"规则，没有 Earnings（同
> 第 9.1 节确认过的业务规则：多公司加总没有明确的持股归属意义）。

### 16.1 核心：不用写新 SQL

第 13 节已经写好的 `aggregateWinLossByRoleAndCurrency`/`aggregateCrDrByRoleAndCurrency` 这两条查询，
`tenantIds` 参数本来就是 `List<Integer>`——单公司版只是"传了一个元素的列表"。Company: All 场景直接把
这个 Group 标签下所有子公司的 id 传进去，SQL 里的 `COALESCE(SUM(...))` 自动把这些公司同一个币种的数字
加总，**不需要新写查询**——这点跟 Group Currency Tab（第 15 节需要新写"tenant+currency 双维度分组"的
SQL）不一样，Company: All 更简单。

### 16.2 后端：`getKpiCurrencyBreakdownForCompanies()`

```
GET /api/dashboard/kpi-all/currency-breakdown?tenant_ids=&date_from=&date_to=&base_currency=
```

逻辑跟 `getKpiCurrencyBreakdown()`（第 13 节）几乎一致，区别只有两处：
1. 币种清单里"该 tenant 配置的币种"这一步，单公司版是 `CurrencyDao#findCurrencyByTenantId`（单个 id），
   这次新增 `findCurrencyByTenantIds`（批量，一条 `WHERE tenant_id IN (...)`，不是每家公司查一次）。
2. ~~不算 Earnings——`earnings`/`earningsConverted` 两个字段固定 `null`~~——**已补上**，见第 19 节
   （复用同一个 `DashboardCurrencyAmountDTO`，不新建 DTO）。

### 16.3 前端接入

`springCompaniesCurrencyBreakdownData`（新 state），复用现有 `groupAllMode`/`groupAllTenantIds`（`/kpi-all`
KPI 卡片本来就在用的同一套 scope 判断和公司列表解析），不用新写判断逻辑。`earningsCurrencyRows` 新增
`groupAllMode` 分支读这份数据；`useConvertedEarnings`/`allCurrencyEarningsReady` 同样加了对应分支，判断
标准跟单公司/Group 版一致（"响应是否落地"）。

### 16.4 真实验证

IG 这个 Group 标签下的子公司 95/AG/RS/CX（tenant_id 2/5/3/6），2026 年 8 月，`base_currency=MYR` 时
`MYR` 行算出 **220,199.32**——跟截图里 "Net Profit 220,199.32" 完全一致（也正好等于第 14 节 Group Net
Profit Tab 里 AG+95+RS+CX 四家公司 Net Profit 直接相加：`183,029.94+26,220.36+16,349.02-5,400.00`）。

### 16.5 尚未覆盖 / 未验证

- 只做到后端真实数据验证 + 前端 `vite build` 编译通过，没有实机登录浏览器肉眼确认表格渲染正常
- `VG` 这家公司没有传进这次验证用的 `tenant_ids` 列表（截图里显示 0.00，原因同第 14.4 节——`company_tenant_ids`/`tenant_ids` 由前端算好传入，不影响这条规则本身）

---

## 17. Company: All Earnings：批量降级链路

> 范围：这次改了主意——第 9.1 节原本商量好 Company: All 不做 Earnings，现在要加上。**只做了 KPI 卡片
> 的 Earnings 金额本身**，Currency Tab 的 Earning 列、Trend Chart 的 Earnings 线、"较上一期"对比这三样
> 还没做，是下一步。这次纯后端改动，`Controller` 和接口路径/参数都没变——`GET /api/dashboard/kpi-all`
> 这个接口本来就有 `showEarnings`/`earnings` 这两个字段位置（单公司版一直在用），只是 Company: All 这
> 条路径之前固定传 `false`/不填，这次把算法接上。

### 17.1 需求：为什么不能"先加总再乘一个百分比"

用户提的算法：`All Earnings = Σ 每家公司各自的 Earnings`，不是 `(Σ每家公司NetProfit) × 一个统一的百分比`。
原因是同一个 Group 标签下的几家公司，持股链路可能完全不一样——A 公司可能对当前登录身份有直接持股，
B 公司可能没有直接持股、要借道它分给的 Group 再算一层，C 公司可能两条都没有（贡献 0）。所以每家公司
必须**独立**走一遍第 11 节那套"直接持股优先、查不到就借道 Group 降级"的判断，算出自己的 Earnings，
最后把这些独立算出来的金额加总——不是共用一个百分比。

### 17.2 性能：如何避免"每家公司查一次"变成 N+1

这是这次讨论花时间最多的地方。原始想法（每家公司调一次现成的 `resolveEffectiveEarningsPercentage()`）
会导致持股%这部分的查询次数随 `tenant_ids` 里的公司数量线性增长——公司越多越慢，用户明确要求不能这样。

**解法：把这个方法整个改写成批量版 `resolveEffectiveEarningsPercentagesForTenants(tenantIds, dateTo,
ownerType, allowGroupCascade)`**，固定 3 条 SQL 以内，不管 `tenantIds` 有多少个：

```
Step 1（1 条 SQL）：批量查这批 tenant 里，哪些对当前登录身份有直接持股行
                    （findLiveOwnershipForTenants / findHistoricalOwnershipForTenants）
Step 2（1 条 SQL）：Step 1 没查到直接持股的那些公司，批量查它们各自分给了哪个 Group
                    （findCompanyGroupAllocationsForTenants / 历史版）
Step 3（1 条 SQL）：Step 2 查出来的 Group id 去重后，批量查登录身份在这些 Group 里各自的直接持股%
                    （复用 Step 1 同一条批量查询方法，只是这次传 Group id 列表）
```

第 3 步复用第 1 步同一条 DAO 方法（`findLiveOwnershipForTenants` 不关心传进去的 id 是公司还是 Group，
`tenant_ownership` 表本来就是同一张表），所以**只新增了 4 条 DAO 查询**（Step 1/2 各自的当月版+历史版），
不是 6 条。业务规则（直接持股优先、`allowGroupCascade=false` 时不降级、一家公司最多分给一个 Group）
跟单公司版 `resolveEffectiveEarningsPercentage()` 完全一致，只是"查询"这一步从循环变成批量。

新增 `DashboardDao` 方法（`backend/src/main/resources/mybatis/DashboardMapper.xml` 对应 4 条 SQL，全部
把单 tenant 版本的 `WHERE tenant_id = ?` 换成 `WHERE tenant_id IN (...)`，业务逻辑一字不改）：

```java
List<TenantOwnership> findLiveOwnershipForTenants(tenantIds, accountId, ownerType);
List<TenantOwnershipHistory> findHistoricalOwnershipForTenants(tenantIds, accountId, ownerType, effectiveMonth);
List<TenantOwnership> findCompanyGroupAllocationsForTenants(tenantIds);
List<TenantOwnershipHistory> findHistoricalCompanyGroupAllocationsForTenants(tenantIds, effectiveMonth);
```

### 17.3 后端：`getKpiForCompanies()` + `computeCompaniesEarnings()`

```
GET /api/dashboard/kpi-all?tenant_ids=&date_from=&date_to=&currency=
```
（接口不变，`DashboardKpiDTO` 的 `showEarnings`/`earnings` 字段这次真正填上了）

`computeCompaniesEarnings(tenantIds, dateFrom, dateTo, currency, ownerType)`：
1. 调 17.2 的批量方法拿到 `Map<tenantId, percentage>`——**只包含算出了非零百分比的公司**，一家公司没
   有直接持股、也没有走通降级的，直接不在这个 Map 里（贡献 0，不是显式存一个 0 进去）。
2. 这个 Map 是空的 → 返回 `null`，`showEarnings=false`，跟单公司"没配置持股就不显示卡片"的行为一致。
3. 批量查这批公司各自的 Win/Loss+Cr/Dr（复用 `aggregateWinLossByRoleAndTenant`/`aggregateCrDrByRoleAndTenant`，
   Group Profit 那边本来就在用的同一条查询，1 次调用不随公司数量变化），算出每家公司自己的 NetProfit。
4. 遍历 Map 里有百分比的公司：`companyEarnings = companyNetProfit × percentage / 100`（`earningsFrom()`，
   跟单公司同一个方法），全部加总就是 `dto.earnings`。

### 17.3.1 Bug：前端 `kpi` useMemo 的 `groupAllMode` 分支硬编码 `showEarnings: false`

**现象**：17.3 的后端改完、真机刷新 Company: All 页面（`Group ID: IG` + `Company: All`），Earnings 卡片
还是没有出现——只有 Profit/Expenses/Net Profit 三张卡片。

**排查**：`useDashboardPage.js` 的 `kpi` useMemo 里，`groupAllMode` 分支是这次改动之前写的，那时
`getKpiForCompanies()` 确实固定返回 `showEarnings=false`，所以前端也照抄了 `showEarnings: false,
earnings: 0` 写死在分支里——这次后端把 17.3 接上之后，前端这个写死的分支完全没跟着更新，一直在用旧的
假数据覆盖掉后端已经算好的真实 `showEarnings`/`earnings`。

**修复**：`groupAllMode` 分支直接改成调 `buildKpiFromSpringPayload(springKpiAllData)`——这是单公司分支
本来就在用的同一个函数，已经处理好了 `showEarnings`/`earnings` 读取，以及 `previous*` 字段为 `null`
时不伪造 `comparisons` 这些细节，不需要重新写一份。`kpi-all` 这个接口目前还没有 `previousProfit` 等字段
（17.5 里提到的"较上一期"对比还没做），所以这次改完 `comparisons` 仍然是空对象，行为上不会多显示涨跌
箭头——只是 Earnings 卡片本身终于会正确出现了。

**教训**：这次是"后端字段加上了，但前端消费那一层没跟着改"——因为 KPI 卡片的读取逻辑（`kpi` useMemo）
分成了好几个 scope 各自的分支（单公司/Group/Company:All），改动只针对了后端 DTO，没有同时检查每个
消费该 DTO 的前端分支是不是也需要跟着放开写死的字段。以后改"接口返回值形状"这种改动，要记得同步检查
前端有没有类似 `groupAllMode` 这种"手写了一份旧行为副本、没有复用现成转换函数"的分支。

### 17.4 真实验证（意外验证了历史月份快照读取路径）

IG 这个 Group 标签下的 95/AG/RS/CX（tenant_id 2/5/3/6），K 账号（account_id=3，`owner_type='owner'`），
2026-08-01～08-31，MYR：

```
Profit = 401,150.32    Expenses = -180,951.00    NetProfit = 220,199.32   （跟截图完全一致）
Earnings = 171,360.68
```

用当前（live）配置口算过一遍，算出来是 133,280.53，跟实际跑出来的 171,360.68 对不上——排查后发现是
**历史月份快照口径的问题，不是 bug**：K 账号在 IG 的持股比例，`tenant_ownership`（live 表）现在是
70%，但 `tenant_ownership_history` 里 `effective_month='2026-08-01'` 那一行存的是 **90%**（8 月当时的
快照，后来改过）。查询逻辑因为 `YearMonth.from(dateTo)`（2026-08）不等于当前月份（系统当前是 2026-09），
正确地走了历史表分支、用了 90% 而不是 70%。用 90% 重新手算：

```
AG: 183,029.94 × 100% × 90% = 164,726.95
95: 26,220.36 × 30% × 90% = 7,079.50
RS: 16,349.02 × 30% × 90% = 4,414.24
CX: -5,400.00 × 100% × 90% = -4,860.00
合计 = 171,360.68
```

跟实际输出完全一致——说明这次新写的批量降级逻辑，"当月查 live 表 / 历史月份查快照表"这条分支切换
也是对的，不是凑巧算对，是历史数据本身就跟当前配置不一样，代码正确反映了这一点。

**验证方法**：`resolveOwnerType()`/`findOwnershipPercentage()` 这些依赖真实登录 session
（`SecurityUtils.currentUser()`），这次没有真机登录，而是在一次性 test 里手动构造了一个
`LoginUserPrincipal`（`user_type="owner"`, `user_id=3`）塞进 `SecurityContextHolder`，绕开真实登录流程
直接测 service 方法——测完立刻删掉了这个 test 文件，不是留在代码库里的常驻测试。

### 17.5 尚未覆盖 / 未验证

- ~~Currency Tab 的 Earning 列（`getKpiCurrencyBreakdownForCompanies()`，见第 16 节）——目前 `earnings`/
  `earningsConverted` 还是固定 `null`，这次批量降级逻辑还没接进按币种拆分那条路径~~——**已做**，见第 19 节
- ~~Trend Chart 的 Earnings 线（`getTrendForCompanies()`）——同样没做~~——**已做**，见第 18 节
- "较上一期" `previousEarnings` 对比——`getKpiForCompanies()` 目前只算了当期，上一期区间
  （`resolvePreviousRange()`，第 7.1 节已有）还没接上 Earnings 这条
- 只做到后端真实数据验证（用伪造的 `SecurityContext` 测的），没有真机登录浏览器肉眼确认 Company: All
  的 Earnings 卡片渲染正常
- 只验证了"4 家公司全部需要走降级链路、且全部借道同一个 Group"这一种真实场景，没有验证过"部分公司
  有直接持股、部分公司需要降级、部分公司两条都没有"混合在同一次请求里的情况——理论上代码逻辑是按
  这种混合场景设计的（17.2 的 Step 1 天然会把有直接持股的公司分流出去，不会进入 Step 2/3），但没有
  拿真实数据凑出这么一个混合场景测过

---

## 18. Company: All Trend Chart Earnings 线：公司 × 月份双批量

> 范围：`GET /api/dashboard/chart-all` 走势图的 Earnings 线。跟第 17 节 KPI 卡片的 Earnings 金额是
> 同一批工作的下半场——第 17 节做的是"批量公司，单一日期"，这次要的是"批量公司 **且** 批量月份"（走势
> 图区间可能横跨好几个月，每一天要用它自己所在月份的持股%去乘，不是整个区间共用一个百分比，这点
> 跟单公司 Trend Chart、Group Trend Chart 的规则一致，见 8.1.1/10.4 节）。不新增端点——`DashboardTrendPointDTO`
> 早就有 `earnings` 字段，这次是"给已有字段补数据"。

### 18.1 后端：2 条新 SQL，"公司+月份"双重 `IN` 一次查完

第 17.2 节的批量方法只批量了"公司"这一个维度（一次查询只对应一个日期）。这次每一步都要**同时**批量
"公司"和"月份"两个维度，所以在 `DashboardMapper.xml` 里新增了 2 条 SQL（`WHERE tenant_id IN (...) AND
effective_month IN (...)`，两个 `IN` 都在同一条查询里）：

```java
List<TenantOwnershipHistory> findOwnershipPercentagesForTenantsAndMonths(tenantIds, accountId, ownerType, effectiveMonths);
List<TenantOwnershipHistory> findCompanyGroupAllocationsForTenantsAndMonths(tenantIds, effectiveMonths);
```

分别是 `findOwnershipPercentagesByMonths`（单 tenant、批量月份）和 `findCompanyGroupAllocationsByMonths`
（同上）的"批量 tenant"版——四个方法当中现成的两个只批量了月份，这次让它们也能批量 tenant，一次查询
拿到"这批公司 × 这批月份"的完整网格，不是循环。

### 18.2 Service：三层封装，跟 17.2 的三步骤对应

```
resolveOwnershipPercentagesForTenantsByMonth(tenantIds, ...)
    → 18.1 新 SQL（历史月份）+ findLiveOwnershipForTenants（当月，17.2 已有）
    → Map<tenantId, Map<YearMonth, percentage>>

resolveCompanyGroupAllocationsForTenantsByMonth(tenantIds, ...)
    → 18.1 新 SQL（历史月份）+ findCompanyGroupAllocationsForTenants（当月，17.2 已有）
    → Map<tenantId, Map<YearMonth, GroupAllocation>>

resolveEffectiveEarningsPercentagesForTenantsByMonth(tenantIds, dateFrom, dateTo, ownerType, allowGroupCascade)
    → 直接持股优先，查不到再看有没有 Group 分配，两个都有才用"分配% × Group 持股%"
    → 每家公司、每个月份各自独立判断（跟 17.2 一样，不是共用一个百分比）
    → Map<tenantId, Map<YearMonth, percentage>>
```

第三层内部会再调一次 `resolveOwnershipPercentagesForTenantsByMonth`（这次传的是查出来的 Group id 列表，
不是公司列表）——跟 17.2 的"Step 3 复用 Step 1 同一条方法"是同一个套路。

**实际查询次数**：`resolveOwnershipPercentagesForTenantsByMonth`/`resolveCompanyGroupAllocationsForTenantsByMonth`
各自最多 2 条 SQL（历史批量 1 条 + 当月批量 1 条，区间不含当月或不含历史月份时更少）。
`resolveEffectiveEarningsPercentagesForTenantsByMonth` 内部调用了 3 次这两个方法（公司的直接持股、公司
的 Group 分配、Group 的直接持股），所以**最多 6 条 SQL**，不是字面意义的"固定 2 条"——但关键是这 6 条
**不会因为公司数量或走势图区间跨了几个月而增加**，横跨 1 个月是这个数，横跨 12 个月还是这个数。

`applyCompaniesTrendEarnings(points, tenantIds, dateFrom, dateTo, currency, ownerType)`：
1. 调用上面的批量方法拿到 `Map<tenantId, Map<YearMonth, percentage>>`；整个 Map 是空的（没有一家公司
   有任何持股）→ 所有日期的 `earnings` 直接设成 `0`，跟单公司版"缺失月份按 0% 处理"的规则一致。
2. 批量查这批公司每一天的 Win/Loss+Cr/Dr（复用 `aggregateWinLossByRoleAndTenantAndDate`/
   `aggregateCrDrByRoleAndTenantAndDate`——Group Trend Chart 已经在用的同一条查询，不是新写的）。
3. 逐天遍历 `points`：算出这一天属于哪个月，再遍历每家公司，查表拿到这家公司这个月的百分比（没有就
   跳过，不计入），乘上这家公司这一天的 NetProfit，所有公司当天加总写回 `point.earnings`。

### 18.3 真实验证

IG 这个 Group 的 95/AG/RS/CX（tenant_id 2/5/3/6），K 账号（account_id=3），2026-08-01～08-31，MYR：

把 `getTrendForCompanies()` 返回的 31 个点的 `netProfit`/`earnings` 各自加总：

```
Σ netProfit = 220,199.32   （跟第 16 节 Currency Tab、KPI 卡片的 Net Profit 完全一致）
Σ earnings  = 171,360.68   （跟第 17 节 KPI 卡片的 Earnings 完全一致，只有最后一位小数因为
                             按天各自四舍五入累加有 0.00001 的浮点误差，可以忽略）
```

**两条完全独立的代码路径**（第 17 节是"批量公司、单一日期，算一个总数"；这次是"批量公司、批量月份，
逐天算完再加总"）算出同一个结果，是比单独跑一次更强的交叉验证——不是同一段代码跑两次凑巧对上，是
两种不同的实现方式从两个方向殊途同归。

### 18.4 前端：这次不需要改代码

查完发现前端早就是"通用"写法，不需要为这个功能专门改：
- `chartRows` 里 `groupAllMode` 分支本来就在复用 `buildSpringTrendChartRows()`——跟单公司/Group Trend
  Chart 用的是同一个函数，这个函数只是单纯读 `point.earnings` 字段，不关心数据是从哪个 scope 来的。
- Earnings 这条线要不要画出来（`chartSeries`），前端读的是 `kpi.showEarnings`——这个在第 17.3.1 节
  已经修过了（`groupAllMode` 分支从硬编码 `false` 改成了 `buildKpiFromSpringPayload()`）。

所以这次只更新了一处过时的注释（`chartRows` useMemo 上面那段说明，原本写着"kpi-all 没有 Earnings
线"，现在已经不对了），没有实际的前端逻辑改动——`vite build` 编译通过。

### 18.5 尚未覆盖 / 未验证

- 只做到后端真实数据验证（伪造 `SecurityContext` 测的，测完删掉），没有真机登录浏览器肉眼确认走势图
  上 Earnings 那条线的形状/数字正常
- 同第 17.5 节：只验证了"全部公司都要走降级链路、且都借道同一个 Group"这一种场景，没有测过直接持股/
  降级/两者皆无混合出现的情况

---

## 19. Company: All Currency Tab 的 Earning 列：补上最后一块拼图

> 范围：第 16 节做 Company: All Currency Tab 时，`earnings`/`earningsConverted` 两个字段固定写死
> `null`（当时的业务规则：多公司加总没有明确持股归属）。后来第 17/18 节改了主意，把 Company: All 的
> KPI 卡片 Earnings、Trend Chart Earnings 线都补上了，Currency Tab 这个缺口从那时候起就被记在第
> 16.5/17.5 节的"尚未覆盖"里，一直没有跟上。这次把它补齐——`GET /api/dashboard/kpi-all/currency-
> breakdown` 这一个端点的 `earnings`/`earningsConverted` 字段接上真实算法，**接口路径和参数都没变**。

### 19.1 算法：跟 KPI 卡片、Trend Chart 用同一套规则，按币种展开

跟第 17 节 KPI 卡片 Earnings 的道理完全一样：**不能**"这个币种的总 Net Profit × 一个共享百分比"，
因为同一批公司的持股/降级路径可能完全不同。正确算法是每个币种各自独立算：

```
Earning(某币种) = Σ 每家公司 的 (这家公司在这个币种下的 Net Profit × 这家公司自己的有效持股%)
```

"有效持股%"就是第 11 节那套"直接持股优先，没有就借道 Group 降级"的判断，每家公司各自独立走一遍。

### 19.2 后端：复用第 15/17 节已有的查询，没有新写 SQL

`getKpiCurrencyBreakdownForCompanies()` 里新增的部分，两块都是直接复用现成的方法，一条新 SQL 都
没写：

1. **按公司+按币种的 Win/Loss、Cr/Dr**——`aggregateWinLossByRoleAndTenantAndCurrency`/
   `aggregateCrDrByRoleAndTenantAndCurrency`，第 15 节 Group Currency Tab 已经在用的同一对查询，
   批量传 `tenantIds`，一次查完，不会因为公司数量或币种数量增加请求量。
2. **每家公司自己的有效持股%**——`resolveEffectiveEarningsPercentagesForTenants(tenantIds, dateTo,
   ownerType, allowGroupCascade=true)`，第 17 节 KPI 卡片 Earnings 已经在用的批量方法，同样是固定
   次数的查询。
3. **加权求和**——直接复用了这次代码优化阶段刚抽出来的 `sumWeightedGroupProfit()` 工具方法（本来是
   给"Net Profit × 股权%"用的），这里把"股权%"换成"有效持股%"，循环逻辑一字不改。
4. 没有任何公司有持股%（比如登录身份是纯 member）时，加权求和结果自然是 `0`——刚好符合 Earning
   Tab"没活动/没持股要显示 `0` 而不是 `—`"的既有规则（第 13 节），不需要额外分支判断。

### 19.3 前端：修复了一个"忘记跟着改"的硬编码

`useDashboardPage.js` 的 `earningsCurrencyRows` 这个 useMemo，`groupAllMode` 分支在后端还没实现
Earning 列的时候，把 `earnings`/`earningsConverted` 写死成了 `null`（跟第 17 节修过的那个 KPI 卡片
`showEarnings: false` 硬编码是同一类问题）。这次后端接上真实算法后，这处前端代码没有跟着改，导致
即使后端已经算出真实数字，前端 Earning Tab 还是显示"—"。

修复：把这两个字段改成跟单公司/Group 分支一样，读取后端返回的真实值：

```js
earnings: row.earnings != null ? parseFloat(row.earnings) : null,
earningsConverted: row.earningsConverted != null ? parseFloat(row.earningsConverted) : null,
```

顺手把上面那条过时的注释（"No Earning tab for this scope...earnings always null"）也一起改掉了。

### 19.4 真实验证

IG 这个 Group 标签下的子公司 95/AG/RS/CX（tenant_id 2/5/3/6），2026 年 8 月，`base_currency=MYR`：

Currency Tab 里 `MYR` 这一行算出的 `earnings` = **171,360.67951977**，跟第 17 节 KPI 卡片 Company:
All 的 Earnings 基线数字（这个场景下所有交易都发生在 MYR，所以两者应该完全相等）精确对上——交叉验证
了新算法正确。

### 19.5 尚未覆盖 / 未验证

- 只做到后端真实数据验证（伪造 `SecurityContext` 测的，测完删掉）+ 前端代码审查，没有真机登录浏览器
  肉眼确认 Company: All 的 Earning Tab 渲染正常（用户反馈过一次"整个面板空白"，但那次截图的日期范围
  是单独一天，跟这次前端字段修复是否完全解决了显示问题还没有确认）
- "较上一期" `previousEarnings` 对比——Currency Tab 本来就没有"较上一期"这个维度（KPI 卡片才有），
  不适用
- 同第 17.5/18.5 节：只验证了"全部公司都要走降级链路、且都借道同一个 Group"这一种真实场景，没有验证
  过直接持股/降级/两者皆无混合出现在同一次请求里的情况
- "较上一期"对比这次也没有涉及 Trend Chart（Trend Chart 本来就没有"较上一期"的概念，第 7 节那是 KPI
  卡片专属功能）
