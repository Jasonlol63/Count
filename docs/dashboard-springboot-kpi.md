# Dashboard KPI 卡片 — 接入 Spring Boot API

> **范围**：Dashboard 页面 4 张 KPI 卡片（Profit / Expenses / Net Profit / Earnings）。
> 新增后端：`DashboardController` / `DashboardService(Impl)` / `DashboardDao` +
> `DashboardMapper.xml` + `DashboardKpiDTO` / `DashboardKpiRoleAmount`。
> 前端改动：`useDashboardPage.js` 新增一个独立的 `GET /api/dashboard/kpi` fetch effect。
> Trend Chart、Earnings Summary、币种明细、Group/Company-All 等其余 Dashboard 功能
> **仍然走旧 PHP**（`dashboard_bootstrap_api.php`），没有迁移。
> **最后更新**：2026-09-07

---

## 1. 新增的后端能力

`GET /api/dashboard/kpi?tenant_id=&date_from=&date_to=`

| 层 | 文件 | 作用 |
|----|------|------|
| Controller | `backend/src/main/java/com/eazycount/controller/DashboardController.java` | 接收 `tenant_id`（数字 id 或 "C168" 这种 code）、`date_from`、`date_to`；`tenant_id` 为 code 时复用 `TenantOwnershipController` 同款的 `TenantDao.findTenantByCode` 解析成 id |
| Service | `backend/src/main/java/com/eazycount/service/impl/DashboardServiceImpl.java` | 核心计算逻辑（见第 2 节） |
| Dao / Mapper | `backend/src/main/java/com/eazycount/dao/DashboardDao.java` + `backend/src/main/resources/mybatis/DashboardMapper.xml` | 两条聚合 SQL（Win/Loss、Cr/Dr）+ 两条股权查询（当前 / 历史） |
| DTO | `backend/src/main/java/com/eazycount/dto/DashboardKpiDTO.java`、`DashboardKpiRoleAmount.java` | 响应体 / SQL 行映射 |

### 计算逻辑

- 只支持 `tenant_type = COMPANY` 的租户；GROUP 类型的 tenant 直接返回全 null（`showEarnings=false`，其余字段 null），前端渲染成 `-`。Group 级别的汇总还没做。
- **Profit** = Win/Loss 桶(role=PROFIT) + Cr/Dr 桶(role=PROFIT)
- **Expenses** = Win/Loss 桶(role=EXPENSES) + Cr/Dr 桶(role=EXPENSES)
- **Win/Loss 桶**：`WIN`(+) / `LOSE`(−) / `ADJUSTMENT`(原样) 记在 `account_id` 一侧，外加手动 `PROFIT` 类型转账（To −，From +），按 `account.role` 分组求和。
- **Cr/Dr 桶**：`PAYMENT` / `CLAIM` / `CONTRA` / `RATE`（仅主腿，排除 RATE 中间人手续费行）To(−) / From(+)，按 `account.role` 分组求和。**`CLEAR` 被有意排除**（见第 3 节）。
- **Net Profit** = `profit + expenses`（`expenses` 本身是带符号的负数，见第 4 节的修复）。
- **Earnings**（第 4 张卡）：只反映当前登录身份自己的股权份额 —— 只有 Owner 登录，或 `role=PARTNERSHIP` 的 admin 登录才可能命中 `tenant_ownership` / `tenant_ownership_history`；member（分账户）登录永远看不到 Earnings 卡。当月用 `tenant_ownership`（live），非当月用 `tenant_ownership_history`（按月快照，`effective_month` = 该月 1 号）。`earnings = netProfit * percentage / 100`（`RoundingMode.HALF_UP`，scale=8）。

## 2. 前端接入

`useDashboardPage.js`（`~L1391`）新增一个独立的 `useEffect`，条件满足时直接 `fetch(/api/dashboard/kpi)`：

- **触发条件** `isSingleCompanyKpiScope`（`~L1277`）：`companyId` 非空 + 不是 Group All / Company All / 不是 group ledger 模式 + 不是多公司 subset 合并。也就是**新接口只支持单一 COMPANY 租户**这一种最简单场景。
- 其它场景（Group 账本、Company All、Group All、多公司合并）目前**没有 Spring KPI 后端**，`springKpiData` 直接清空，KPI 卡片显示为空 / `-`。
- 这个 fetch 是刻意做得很简单的：没有走 `dashboard_bootstrap_api` 那套 cache / dedup / prefetch 机制（`dashboardRoutePrefetch.js` 里对应的预热函数被改成了显式 no-op），注释里写明这是感知性能优化、不是正确性需求，重新接线不值当。
- 消费端在 `~L8734`：`springKpiData.profit/expenses/netProfit/showEarnings/earnings` 原样透传给 KPI 卡片，前端不做二次计算。

## 3. 已知的口径问题（尚未处理，需要业务确认）

**`CLEAR` 类型交易被排除在 Cr/Dr 桶之外**（`DashboardDao.java:25` 注释、`DashboardMapper.xml:79-80`）。

- 技术分类上 `CLEAR` 和 `PAYMENT`/`CLAIM`/`CONTRA` 是同一挂 Cr/Dr 类型交易（见 `TransactionSubmitService.java:5` 注释），不是 Win/Loss。
- 但业务用法上，`CLEAR` 通常是操作人员按账户当前 Win/Loss 余额去开的一笔"结清"交易，作用是把该账户的 Win/Loss 余额清零。
- Transaction Search 页面的 Total 表格（`TransactionSearchMapper.xml` 里的 `aggregateDomainPaymentCrDr`，`manualCrDrTransactionTypes` 包含 `CLEAR`）会把 `CLEAR` 计入 Cr/Dr，因此 Win/Loss + Cr/Dr 通常能相互抵消到 Balance = 0（已结清账户）。
- Dashboard 的 Profit/Expenses 因为排除了 `CLEAR`，数值会等于"这段时间的全部 Win/Loss（含未结清部分）+ 非 CLEAR 的 Cr/Dr"，和 Transaction Search 报表里"已结清净额"的口径对不上，数字会明显偏大。
- 实测案例（Company 95，2026-08-01 ~ 2026-08-31，IG 组）：Transaction Search Total 表格 Win/Loss = 71,253.36，Cr/Dr = −71,253.36（含 CLEAR），Balance = 0；Dashboard Profit 卡片显示 80,145.78，差额 8,892.42 正好是该期间非 CLEAR 的 Cr/Dr（PAYMENT/CLAIM/CONTRA/RATE）净额。
- **待决策**：Profit/Expenses KPI 要不要把 `CLEAR` 也纳入 Cr/Dr 桶，让它和 Transaction Search 报表口径一致？—— 这是业务口径选择，不是 bug，需要产品/业务侧确认后再改 `DashboardDao`/`DashboardMapper.xml` 里的类型列表。

## 4. Bug 修复记录

### Net Profit 加减号错误（已修复）

`DashboardServiceImpl.getKpi()` 里原来写的是：

```java
BigDecimal netProfit = profit.subtract(expenses);
```

`expenses` 本身已经是带符号的负数（EXPENSES 角色净下来是借方/支出方向），`subtract` 会造成双重取反，等价于 `profit + |expenses|`（把支出当成加法），导致 Net Profit 虚高。例如 Profit=1,680.00，Expenses=−6,136.36 时，算出来是 `1680 − (−6136.36) = 7,816.36`（错误，应为亏损）。

修复为：

```java
// expenses is already signed negative (EXPENSES role nets to a debit/outflow), so a
// plain add gives profit - |expenses|; subtract would double-negate into profit + |expenses|.
BigDecimal netProfit = profit.add(expenses);
```

修复后 `1680 + (−6136.36) = −4,456.36`，等价于纯减法 `1680 − 6136.36`，与预期一致。前端 `springKpiData.netProfit` 是直接透传显示的，没有二次计算，因此不需要改前端。

## 5. 尚未覆盖的范围

- Group 账本（group ledger）、Company All / Group All 合并视图、多公司 subset 合并场景的 KPI 计算，仍在 PHP 那边，没有迁移到 Spring。
- Trend Chart、Earnings Summary（右侧圆环图）、按币种拆分的 Currency/Amount/Share 明细，目前没有确认是否也依赖旧 PHP 接口（还需要单独排查）。
- `CLEAR` 排除的口径问题（见第 3 节）待业务确认后再改。
