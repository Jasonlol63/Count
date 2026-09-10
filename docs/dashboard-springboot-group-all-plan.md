# Group: All 功能实施计划（未开始实施）

> **状态**：这是一份实施前的设计文档，记录讨论好的方向和算法，**代码还没有开始写**。
> 跟 `dashboard-springboot-kpi.md`（已完成功能的真机验证记录）、
> `dashboard-springboot-kpi-optimization.md`（已完成的代码重构记录）性质不同——这两份是"做完了写的"，
> 这份是"准备做之前先写的"，等实施完会把真实的进度和验证结果并入 `dashboard-springboot-kpi.md`。
> **创建日期**：2026-09-10

---

## 目录

1. [要做什么](#1-要做什么)
2. [跟 Company: All 的核心区别](#2-跟-company-all-的核心区别)
3. [怎么避免因为 Group/公司数量增加而变慢](#3-怎么避免因为-group公司数量增加而变慢)
4. [Profit / Expenses / Net Profit：可以摊平求和](#4-profit--expenses--net-profit可以摊平求和)
5. [Earnings：必须按 Group 分桶，不能摊平](#5-earnings必须按-group-分桶不能摊平)
6. [Trend Chart：同一套算法多一个日期维度](#6-trend-chart同一套算法多一个日期维度)
7. [Currency Tab：同一套算法多一个币种维度](#7-currency-tab同一套算法多一个币种维度)
8. [三个 Tab 要不要合并成一个 API？](#8-三个-tab-要不要合并成一个-api)
9. [需要新增/复用的方法清单](#9-需要新增复用的方法清单)
10. [前端接口约定（待确认）](#10-前端接口约定待确认)
11. [尚待确认的问题](#11-尚待确认的问题)

---

## 1. 要做什么

Dashboard 页面 "Group ID" 选择器目前是 `All / AP / IG`（截图里看到的样子）。`AP`/`IG` 这些具体 Group
的 KPI 卡片、Trend Chart、Currency Tab 都已经做完（见 `dashboard-springboot-kpi.md` 第 10/15 节）。
这次要做的是选 **"Group ID: All"** 时这三块要展示的东西——原理跟已经做完的 "Company: All"（同一份
文档第 9/16~19 节）一样：**每个 Group 各自独立算出自己的 Profit/Expenses/NetProfit/Earnings，再把这些
结果加总**，不是把底层数据全部摊平之后套一个共同的比例。

---

## 2. 跟 Company: All 的核心区别

| | Company: All | Group: All |
|---|---|---|
| "个体"的 Net Profit 怎么来 | 直接查这家公司自己账本的 Win/Loss+Cr/Dr | **不是**直接查表——是"旗下子公司 Net Profit 按股权% 加权 + Group 自己账本的 Expenses"（`computeGroupKpi()` 那套算法），是一层间接计算 |
| Earnings 的持股判断 | 直接持股优先，没有就借道 Group 降级（第 11 节的降级链路） | **只有直接持股，没有降级链路**（`allowGroupCascade` 永远 `false`，跟现在 `getKpiForGroup()` 的规则一样） |
| Earnings 要不要按"个体"分桶 | 都要（登录身份在不同公司的有效持股%可能不同） | 都要（登录身份在不同 Group 的直接持股%可能不同） |

第一条区别是这次实施的主要难点——Company: All 的所有数字都能"摊平"批量查（`tenant_id IN (...)`
一次 `SUM` 搞定），但 Group: All 的 Profit/NetProfit 因为要经过"加权"这一步，不能简单摊平，需要专门
设计批量算法（见第 3 节）。

---

## 3. 怎么避免因为 Group/公司数量增加而变慢

如果直接写成"对每个 Group 调一次现成的 `computeGroupKpi()`"，查询次数会随 Group 数量线性增长——
这是要避免的。做法是把"每个 Group 各自的加权算法"拆开，**底层数据只批量查一次（不分 Group），只在
最后"求和"这一步才按 Group 拆开算，全程在 Java 内存里处理**：

```
Step 1（2 条 SQL）：这批 Group 旗下所有公司的 Win/Loss + Cr/Dr
                    aggregateWinLossByRoleAndTenant(所有公司id) / aggregateCrDrByRoleAndTenant(...)
                    —— 不分 Group，一次查完所有公司

Step 2（最多 2 条 SQL）：这些公司各自分给了哪个 Group、分了多少%
                    resolveCompanyGroupAllocationsForTenantsByMonth(所有公司id, ...)
                    —— 这个方法本来就不挑 Group，返回结果里带 groupTenantId 字段，
                       在 Java 里按这个字段分桶即可（一家公司最多分给一个 Group，
                       所以分桶不会有重复计算的风险）

Step 3（2 条 SQL）：这批 Group 自己账本的 Profit/Expenses
                    aggregateWinLossByRoleAndTenant(所有Group id) / aggregateCrDrByRoleAndTenant(...)
                    —— 这两个方法本来就是"按 tenant 分组返回"，一次查询就能拿到
                       每个 Group 各自的数字，不用循环

Step 4（最多 2 条 SQL）：这批 Group 自己的直接持股%（Earnings 用，不降级）
                    resolveEffectiveEarningsPercentagesForTenants(所有Group id, ..., allowGroupCascade=false)
                    —— 传 false 时这个方法本来就会跳过降级那几步，直接返回直接持股结果
```

固定 **最多 8 条 SQL**，不管选了几个 Group、每个 Group 底下有几家公司都不会增加——每一步用的都是
已经在 `DashboardDao` 里现成的批量方法，**不需要新写一条 SQL**，只是换一批参数（Group id 列表 /
公司 id 列表）去调用。

---

## 4. Profit / Expenses / Net Profit：可以摊平求和

这三个数字最终只要一个总数，不需要保留"哪个 Group 贡献了多少"这个中间态，所以可以摊平算：

- **Expenses(Group:All)**：直接复用现有 `computeProfitExpenses(groupTenantIds, ...)`——`aggregateWinLossByRole`/
  `aggregateCrDrByRole` 传 Group id 列表，一次查询就是"所有 Group 账本 Expenses 的总和"，不需要
  额外处理。
- **Profit(Group:All)**：第 3 节 Step 1、Step 2 查出来的数据，在 Java 里把"归属于这批 Group 中任意
  一个"的公司挑出来（用 Step 2 分桶时的 `groupTenantId` 是否在目标集合里过滤），直接加权求和（复用
  `sumWeightedGroupProfit()`），不需要先按 Group 拆开再合并。
- **NetProfit = Profit + Expenses**。

---

## 5. Earnings：必须按 Group 分桶，不能摊平

登录身份在不同 Group 里的直接持股% 可能不一样（比如 AP 70%、IG 50%），所以这一步**必须**先算出
"每个 Group 自己的 NetProfit"，再各自乘上这个 Group 自己的持股%，最后加总：

```
Earnings(Group:All) = Σ 每个 Group 的 (这个 Group 自己的 NetProfit × 这个 Group 自己的直接持股%)
```

"这个 Group 自己的 NetProfit" = 第 3 节 Step 1/2 的数据按 `groupTenantId` 分桶算出的加权 Profit
+ 第 3 节 Step 3 里这个 Group 自己的 Expenses——不是重新查一次，是同一份批量数据换一种切法（分桶 vs
摊平）。这一步同样复用 `sumWeightedGroupProfit()`，权重换成"每个 Group 自己的持股%"，被加权的值换成
"每个 Group 自己的 NetProfit"。

---

## 6. Trend Chart：同一套算法多一个日期维度

跟 KPI 卡片同一套算法，底层查询换成按日期版本：

- 公司：`aggregateWinLossByRoleAndTenantAndDate` / `aggregateCrDrByRoleAndTenantAndDate`（批量）
- Group 自己账本：同上，传 Group id 列表（批量）
- 权重（公司分给哪个 Group、Group 自己的持股%）：复用"按月批量"的现成方法
  `resolveCompanyGroupAllocationsForTenantsByMonth`/`resolveEffectiveEarningsPercentagesForTenantsByMonth`
  （这两个方法本来就是按月批量查的，Trend Chart 区间横跨几个月不会增加查询次数，不需要为这次
  新设计）

逐天遍历时：这一天属于哪个月 → 查表分桶（公司归属的 Group、这个 Group 这个月的持股%）→ 加权求和 →
写回 `point.netProfit`/`point.earnings`。跟 `buildGroupTrendPoints()`/`applyCompaniesTrendEarnings()`
现有的组装模式是同一个思路，只是多一层"按 Group 分桶"。

---

## 7. Currency Tab：同一套算法多一个币种维度

再加一个"币种"维度——公司和 Group 各自的批量查询换成 `AndTenantAndCurrency` 版本（这两个方法第
15 节 Group Currency Tab、第 19 节 Company:All Earning 列都已经在用，不是新查询）：

- Net Profit 列：跟 KPI 卡片的 Profit 逻辑一样（按 Group 分桶加权，再按币种把所有 Group 的结果汇总）
- Earning 列：同样要按 Group 分桶（不能摊平），逻辑跟第 5 节一致，只是多套一层"这个币种"

---

## 8. 三个 Tab 要不要合并成一个 API？

**不合并**，维持三个独立端点，理由（详见上一轮讨论，这里记结论）：

1. 项目里已经有过明确的反例决策——Group Net Profit Tab（第 14 节）的代码注释写着"故意做成独立端点，
   只有真的点开那个 Tab 才发请求，没点开就不用付这个查询的代价"，这次延续同样的原则。
2. KPI 卡片要最快返回，不能被 Trend Chart/Currency Tab 更贵的查询拖慢；Currency Tab 经常根本没被
   点开，合并了就是白白多付一次查询代价。
3. 缓存/刷新粒度更细——只改动其中一块（比如只切 Currency 显示币种）不需要把另外两块也重新拉一次。

**折中**：三个 Service 方法内部抽一个共享的私有组装方法（对应第 3 节 Step 1~4 那套批量取数逻辑），
避免三个端点各自重复发起同样的底层查询——拿到"合并"能带来的查询效率收益，同时不牺牲"三个 Tab
独立请求、互不阻塞"这个优点。这样也跟已经做好的 Company: All（三个独立端点）保持架构一致。

---

## 9. 需要新增/复用的方法清单

**DAO / Mapper 层**：不需要新写任何 SQL，全部复用现成的批量方法。

**Service 层**（新增，命名待定）：
- `getKpiForGroups(groupTenantIds, companyTenantIds, dateFrom, dateTo, currencyCode)`
- `getTrendForGroups(groupTenantIds, companyTenantIds, dateFrom, dateTo, currencyCode)`
- `getGroupsCurrencyBreakdown(groupTenantIds, companyTenantIds, dateFrom, dateTo, baseCurrencyCode)`
- 一个私有共享方法（对应第 3 节 Step 1~4，具体切法要看实施时哪些中间结果三个方法都要用）

**Controller 层**（新增 3 个端点，命名待定，参考现有 Company:All 三个端点的命名风格）：
- `GET /api/dashboard/kpi-all-groups`
- `GET /api/dashboard/chart-all-groups`
- `GET /api/dashboard/kpi-all-groups/currency-breakdown`

**前端**（新增，套路照抄 Company:All 那一套，不会有新花样）：
- 3 个新 state + `useEffect`（`springKpiAllGroupsData` 之类命名）
- `kpi`/`chartRows`/`earningsCurrencyRows` 几个 useMemo 里新增对应分支

---

## 10. 前端接口约定（待确认）

新端点预计需要前端传两个参数：
- `groupTenantIds`：这次 "All" 范围内选中的 Group 列表
- `companyTenantIds`：这些 Group 旗下所有公司的并集

这跟现有 `getKpiForGroup(groupTenantId, companyTenantIds, ...)` 已经要求前端传 `companyTenantIds`
是同一个套路，只是这次要多传一个 Group 列表。前端应该只需要把现有"每个 Group 自己的公司列表"拼起来
即可，不需要新的接口去查这份数据——但这一点需要在实施前跟前端那边确认可行。

---

## 11. 尚待确认的问题

在正式动手写代码前，还有几个点需要用户确认：

1. **Service/Controller 方法命名**：第 9 节列的名字是临时占位，正式命名需要确认（比如是否要跟
   `getKpiForCompanies`/`getKpiForGroup` 的命名风格对齐成 `getKpiForGroups`）。
2. **`groupTenantIds`/`companyTenantIds` 具体怎么从前端拿到**：是每个 Group 页面本来就有自己的
   `companyTenantIds`，"All" 场景下前端把这些列表拼起来传过来？还是后端需要自己再查一次
   "这批 Group 各自有哪些子公司"？（如果是后端自己查，又要多一条批量 SQL，但同样可以保证不随
   数量增长）
3. **是否需要"较上一期"对比**：单 Group、Company: All 目前都没有做"较上一期"，Group: All 这次
   要不要一起做，还是保持跟现状一致（先不做，跟 Company: All 的 §17 一样留作后续）。
4. **Currency Tab 的币种清单来源**：单 Group Currency Tab 用的是 `CurrencyDao#findCurrencyByTenantId`
   （单个 Group 自己配置的币种），Group: All 场景下币种清单应该是"这批 Group 各自配置的币种的并集"，
   需要确认 `CurrencyDao` 现成的批量方法（`findCurrencyByTenantIds`）能不能直接拿 Group id 列表用
   （现在这个方法是给 Company:All 用的，传的是公司 id，语义上应该通用，但需要确认表结构上 Group
   和 Company 的币种配置走的是不是同一张表）。

等这几点确认完，就可以按第 3~9 节的设计开始动手实施，并同步真机验证记录回
`dashboard-springboot-kpi.md`。
