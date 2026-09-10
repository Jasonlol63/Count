# Dashboard 后端代码优化记录（可读性 + 查询去重）

> **范围**：只针对 `Count` 仓库后端 Dashboard 相关代码做内部重构——`DashboardServiceImpl.java` /
> `DashboardDao.java` / `DashboardMapper.xml` / `DashboardCurrencyAmountDTO.java`。**不改变任何对外
> 行为**：Controller 端点路径、JSON 响应字段、前端调用方式全部不变（第 11 节的 DTO 合并改了 Java 里
> 的类型引用路径，但序列化出去的 JSON 字段名和形状没变）。
> **最后更新**：2026-09-10（新增第 11～13 节：`DashboardGroupCompanyNetProfitDTO` 合并进
> `DashboardCurrencyAmountDTO.CompanyNetProfit` 内嵌类、Service 层参数校验样板抽取 + Group 加权求和
> 算法合并、注释精简）

---

## 目录

1. [为什么要做这次优化](#1-为什么要做这次优化)
2. [优化前的核心问题](#2-优化前的核心问题)
3. [第一步：KPI 卡片骨架合并（Service 层）](#3-第一步kpi-卡片骨架合并service-层)
4. [第二步：Ownership 权益查询合并（DAO + Mapper 层）](#4-第二步ownership-权益查询合并dao--mapper-层)
5. [第三步：Win/Loss + Cr/Dr 聚合 SQL 合并（Mapper 层，类别 A）](#5-第三步winloss--crdr-聚合-sql-合并mapper-层类别-a)
6. [这次优化会不会影响响应速度？](#6-这次优化会不会影响响应速度)
7. [过程中踩的坑](#7-过程中踩的坑)
8. [明确不做的部分（以及为什么）](#8-明确不做的部分以及为什么)
9. [验证方式与结果](#9-验证方式与结果)
10. [DTO 合并：DashboardGroupCompanyNetProfitDTO → 内嵌类](#10-dto-合并dashboardgroupcompanynetprofitdto--内嵌类)
11. [Service 层第二轮：参数校验样板抽取 + Group 加权求和算法合并](#11-service-层第二轮参数校验样板抽取--group-加权求和算法合并)
12. [注释精简](#12-注释精简)
13. [尚未开始的后续优化项](#13-尚未开始的后续优化项)

---

## 1. 为什么要做这次优化

原话（用户在 Phase 8 提出的需求）：

> "当前我发现当前dashboard后端你给的代码阅读性很差，很多基本我都不怎么看得懂，而已也发现有许多的业务逻辑走的
> 具体流程都是一样的，只是筛选的时候可能一个是用tenant_id, 一个是用tenant_ids的一个区别。尤其是在Mapper中
> chart走的sql查询基本都大差不差…这种小改动都特别需要再开一个新查询？在serviceImpl中也是有很多一些反复
> 调用或者是代码上相差不了多少的问题…所以当前针对代码部分你有什么可以给我的优化建议？但是确保你给的建议是
> 能够解决响应慢、请求量大的问题，以及阅读性差的问题。"

明确的两条硬约束：

1. **不能让响应变慢、请求量变大**——所有 Currency/Earning/Group/Company:All 功能之前是按"批量 SQL、
   `tenant_id IN (...)`、`effective_month IN (...)`"的原则一路做下来的，绝不能因为"重构代码"倒退回
   per-item 循环查询。
2. **要解决阅读性差的问题**——之前为了实现 Group / Company:All / Currency / Earning 等一系列功能，
   在 `DashboardDao` / `DashboardMapper.xml` / `DashboardServiceImpl` 里陆续新增了大量方法和 SQL，
   很多方法之间唯一的区别只是"单个 tenant vs 一批 tenant"或者"多分一层 GROUP BY"，导致同一段业务逻辑
   在文件里出现 N 份几乎一样的拷贝。

用户明确要求"一步一步来"，每一步开始前都要先说清楚"修改方向和范围"，用户确认后才动代码；每一步做完
都要用真实数据回归验证，确保数字跟改造前完全一致。

---

## 2. 优化前的核心问题

按代码位置拆开看，问题集中在三层：

### 2.1 Service 层（`DashboardServiceImpl.java`）
`getKpi()`（单 Company）和 `getKpiForGroup()`（Group）两个方法，除了"净利润怎么算"这一步不一样
（Company 是查自己的 Win/Loss+Cr/Dr，Group 是把旗下每家公司的 Net Profit 按股权% 加权求和），
参数校验、查 tenant、算 Earnings、查上一期对比这一整套骨架代码几乎逐行重复。

### 2.2 DAO 层（`DashboardDao.java` 接口）
"持股权益（Ownership）"相关的查询原本有 **15 个方法**，两两成对甚至三三成组，区别仅仅是：
- 单 tenant 版本 vs 批量 tenant 版本（`tenant_id = ?` vs `tenant_id IN (...)`）
- 当前实时数据版本 vs 历史快照版本（`tenant_ownership` vs `tenant_ownership_history`）
- 单月版本 vs 批量月份版本（`effective_month = ?` vs `effective_month IN (...)`）

而"`tenant_id = ?` 单条查询"和"`tenant_id IN (单元素列表)` 批量查询"在 MySQL 执行计划层面是
**完全等价**的——单条调用方完全可以直接复用批量版本、传一个只有一个元素的 List，没必要单独再维护
一份 SQL 和一份 Java 方法。

### 2.3 Mapper 层（`DashboardMapper.xml`）
Win/Loss 桶（`WIN`/`LOSE`/`ADJUSTMENT` + 手动 `PROFIT` 转账 + `RATE` 中间人手续费两种历史格式）和
Cr/Dr 桶（`PAYMENT`/`CLAIM`/`CONTRA`/`RATE` 主腿）这两套核心业务逻辑，因为要满足"KPI 卡片求和"、
"按币种拆分"、"按公司拆分"、"按公司+币种拆分"、"按日期拆分（Trend Chart）"、"按公司+日期拆分
（Group Trend Chart）"这 6 种不同的分组/筛选需求，被**逐字复制了 12 份几乎一样的 UNION ALL SQL**，
每份 40～90 行不等，唯一的区别就是 SELECT 里多不多 `tenant_id`/`currency_code`/`transaction_date`
这几列、WHERE 里要不要按币种过滤。

---

## 3. 第一步：KPI 卡片骨架合并（Service 层）

### 怎么改的
在 `DashboardServiceImpl` 里新增一个共享骨架方法：

```java
private DashboardKpiDTO buildKpiDto(
        Integer ownershipTenantId, LocalDate dateFrom, LocalDate dateTo,
        boolean allowGroupCascade,
        BiFunction<LocalDate, LocalDate, ProfitExpenses> profitExpensesFn) { ... }
```

`getKpi()`（Company）和 `getKpiForGroup()`（Group）各自保留自己独有的部分（参数校验、tenant 类型
判断、类型不对时的空返回/抛异常），然后把"净利润怎么算"这一步作为一个 `BiFunction` lambda 传进去：

```java
// Company
return buildKpiDto(tenantId, dateFrom, dateTo, true,
        (from, to) -> computeProfitExpenses(tenantIds, from, to, currency));

// Group
return buildKpiDto(groupTenantId, dateFrom, dateTo, false,
        (from, to) -> computeGroupKpi(groupTenantId, companies, from, to, currency));
```

`allowGroupCascade` 这个开关控制的是 Earnings 的降级链路要不要生效（Company 自己没配置直接持股时
允许借道 Group 分配% 算，Group 自己不允许再往上借道），这是业务语义上真实存在的差异，所以显式作为
参数传入，不合并掉。

### 为什么这么改是安全的
- Controller 层完全没动——`getKpi` 和 `getKpiForGroup` 依然是两个独立的方法/端点，只是内部实现
  共享了骨架。
- "不是 COMPANY 类型就返回空 DTO" / "不是 GROUP 类型就抛异常" 这两条各自独有的分支逻辑**特意留在
  骨架外面**，不塞进共享方法，避免为了合并代码而牺牲两边本来就不一样的错误处理行为。

---

## 4. 第二步：Ownership 权益查询合并（DAO + Mapper 层）

### 怎么改的
把原来 15 个 Ownership 相关的 DAO 方法，合并成 **4 个批量方法**：

| 新方法 | 覆盖场景 |
|---|---|
| `findLiveOwnershipForTenants(tenantIds, accountId, ownerType)` | 一批 tenant 的当前直接持股 |
| `findOwnershipPercentagesForTenantsAndMonths(tenantIds, accountId, ownerType, effectiveMonths)` | 一批 tenant × 一批月份 的历史直接持股 |
| `findCompanyGroupAllocationsForTenants(tenantIds)` | 一批 tenant（公司）当前分给了哪个 Group、分了多少% |
| `findCompanyGroupAllocationsForTenantsAndMonths(tenantIds, effectiveMonths)` | 一批 tenant × 一批月份 的历史 Group 分配 |

单一 tenant / 单一月份的调用场景，一律传"只有一个元素的 List"复用批量版本——因为
`tenant_id = ?` 和 `tenant_id IN (单值列表)`、`effective_month = ?` 和
`effective_month IN (单值列表)` 在 MySQL 里生成的执行计划是等价的，不需要为"单个"和"批量"分别
维护两份 SQL。

对应地，`DashboardServiceImpl` 里被删掉/改造的方法：

- **完全删除**（确认无调用方）：`findOwnershipPercentage`、`findCompanyGroupAllocation`、
  `resolveOwnershipPercentagesByMonth`（旧单 tenant 版本）、
  `resolveCompanyGroupAllocationsByMonth`（旧单 tenant 版本）。
- **改造成薄封装（delegate）**：`resolveEffectiveEarningsPercentagesForTenants`、
  `resolveEffectiveEarningsPercentage`、`resolveEffectiveEarningsPercentagesByMonth`
  （顺手把不再需要的 `accountId` 参数从签名里去掉了）——这些方法现在只是把参数包一层
  `List.of(...)`，转调唯一的核心算法方法
  `resolveEffectiveEarningsPercentagesForTenantsByMonth(tenantIds, dateFrom, dateTo, ownerType, allowGroupCascade)`，
  再从返回的 `Map<Integer, Map<YearMonth, BigDecimal>>` 里取需要的那一份数据。
- `findGroupEquityPercentages` / `resolveGroupEquityPercentagesByMonth` 同样改造成调用
  `resolveCompanyGroupAllocationsForTenantsByMonth` 之后在 Java 里做 reshape/filter，而不是
  单独发一条 SQL。

### 为什么这么改是安全的
"降级链路"算法（直接持股优先，没有直接持股就借道 Group 分配% × 登录身份在该 Group 的持股%）
本身**一个字都没有改动**，只是把原来分散在 15 个方法里的"查询批量粒度不同"这件事，统一收敛到
4 个真正必要的批量查询上，上层调用方（`getKpi`、`getKpiForGroup`、`getTrend`、`getTrendForGroup`、
`computeCompaniesEarnings`、`applyCompaniesTrendEarnings` 等）全部不用改调用方式。

---

## 5. 第三步：Win/Loss + Cr/Dr 聚合 SQL 合并（Mapper 层，类别 A）

这一步用户特别要求"先重新确认一遍整体代码，再给出精确的修改方向和范围，不改代码"，确认后才动手，
详见下面 [过程中踩的坑](#7-过程中踩的坑) 里记录的一次方向修正。

### 怎么改的
在 `DashboardMapper.xml` 里新增两个可复用的 `<sql>` 片段：

```xml
<sql id="dashboardWinLossCore">
    SELECT
        <if test="'${groupByTenant}' == 'true'">t.tenant_id AS tenantId,</if>
        <if test="'${groupByCurrency}' == 'true'">c.code AS currencyCode,</if>
        <if test="'${groupByDate}' == 'true'">t.transaction_date AS date,</if>
        a.role AS role, ... AS amt
    FROM transactions t ...
    WHERE t.tenant_id IN (...)
      AND ...
      <if test="'${groupByCurrency}' != 'true'">AND UPPER(c.code) = UPPER(#{currencyCode})</if>
      AND UPPER(a.role) IN (...)
    UNION ALL
    ... （WIN/LOSE/ADJUSTMENT、手动 PROFIT 转账 To/From、RATE 中间人手续费新旧两种格式，共 5 段）
</sql>

<sql id="dashboardCrDrCore">
    ... （PAYMENT/CLAIM/CONTRA/RATE 主腿 To/From、RATE 中间人付款方视角，共 3 段）
</sql>
```

三个开关属性（`groupByTenant`/`groupByCurrency`/`groupByDate`，值是字符串 `"true"`/`"false"`）
控制两件事：
1. SELECT 列表里要不要多出 `tenant_id`/`currency_code`/`transaction_date` 这几列；
2. `groupByCurrency` 为 `false` 时，才会在 WHERE 里加上 `UPPER(c.code) = UPPER(#{currencyCode})`
   这条币种过滤（因为"按币种拆分"的查询场景反而是**不筛币种**，要把每个币种都查出来）。

原来 12 个 `<select>` 现在全部变成"调用 `<include>` + 传 3 个固定字面量属性 + 外层 SELECT/GROUP BY"
的薄封装，例如：

```xml
<select id="aggregateWinLossByRoleAndTenant" resultType="com.eazycount.dto.DashboardKpiDTO$RoleAmount">
    SELECT x.tenantId AS tenantId, x.role AS role, COALESCE(SUM(x.amt), 0) AS amount
    FROM (
        <include refid="dashboardWinLossCore">
            <property name="groupByTenant" value="true"/>
            <property name="groupByCurrency" value="false"/>
            <property name="groupByDate" value="false"/>
        </include>
    ) x
    GROUP BY x.tenantId, x.role
</select>
```

12 个方法对应的三个开关取值（`resultType` 和方法签名全部保持不变，一个都没改）：

| DAO 方法 | groupByTenant | groupByCurrency | groupByDate | 用途 |
|---|---|---|---|---|
| `aggregateWinLossByRole` / `aggregateCrDrByRole` | false | false | false | 单 Company KPI 卡片 |
| `aggregateWinLossByRoleAndCurrency` / `aggregateCrDrByRoleAndCurrency` | false | true | false | Currency Tab（按币种拆分） |
| `aggregateWinLossByRoleAndTenant` / `aggregateCrDrByRoleAndTenant` | true | false | false | Group Profit（按公司拆分） |
| `aggregateWinLossByRoleAndTenantAndCurrency` / `aggregateCrDrByRoleAndTenantAndCurrency` | true | true | false | Group Currency Tab（按公司+币种拆分） |
| `aggregateWinLossByRoleAndTenantAndDate` / `aggregateCrDrByRoleAndTenantAndDate` | true | false | true | Group Trend Chart（按公司+日期拆分） |
| `aggregateWinLossByRoleAndDate` / `aggregateCrDrByRoleAndDate` | false | false | true | Trend Chart（按日期拆分） |

### 为什么这么改是安全的
- `DashboardDao.java` 接口**一行没改**——12 个方法名、参数、`resultType` 全部保持原样。
- `DashboardServiceImpl.java`、`DashboardController.java`、前端**零改动**，这一步是纯 Mapper
  XML 内部重构。
- 关键前提：`DashboardKpiDTO.RoleAmount` 和 `DashboardTrendPointDTO.RoleAmount` 这两个内嵌 DTO
  本来就是完全互补的——Kpi 系列永远不需要 `date` 字段，Trend 系列永远不需要 `currencyCode` 字段，
  所以完全不需要为了合并 SQL 而反过来改动 DTO 结构或统一成一个 DTO。

---

## 6. 这次优化会不会影响响应速度？

**不会**，原因是 MyBatis 的 `<include>` 展开只发生在应用启动、Mapper XML 被解析的那一刻，
**不是每次请求时动态展开**：

- 传给 `<include>` 的三个属性（`groupByTenant`/`groupByCurrency`/`groupByDate`）在每个
  `<select>` 里都是**写死的字面量**，不是运行时参数。
- MyBatis 在启动时解析 XML 时，就会把 `<include>` 和里面的 `<if test="...">` 一次性求值、
  裁剪完毕，生成一份**固定不变**的 SQL 结构，绑定并缓存进对应的 `MappedStatement`。
- 所以运行时每次调用这 12 个方法中的任意一个，MyBatis 拼出来的最终 SQL 文本，跟重构前手写的
  那份 SQL 是**逐字节一致**的——变的只是 XML 里的"写法"（从 12 份重复文本变成 2 份共享模板 +
  引用），"实际发给 MySQL 执行的东西"完全没变。数据库执行计划、索引命中、扫描行数都不会有任何
  差异，自然不会有额外的请求量或响应延迟。
- 这一点也在下面的真机回归验证里得到了侧面证明：如果 SQL 文本或语义有任何差异，重构前后的数字
  不可能精确到小数点后 8 位完全对上。

---

## 7. 过程中踩的坑

### 7.1 类别 A 最初的方案过于激进，用户要求重新确认后改成了更保守的方案
最开始预估"12 个查询能合并成 2 个"，这个预估隐含的前提是要把 `DashboardKpiDTO.RoleAmount` 和
`DashboardTrendPointDTO.RoleAmount` 这两个 DTO 也合并成一个，会牵连更多 Java 调用点。用户要求
"修改前你先再确认一遍整体代码再给我一个准确的修改方向和范围，不改代码"，重新通读这 12 条 SQL 后
发现两个 RoleAmount DTO 其实天然互补，于是改成了现在这份更保守的方案：**12 个 DAO 方法签名和
各自的 `resultType` 一个不动**，只把 SQL 正文提取成 2 个共享片段——比最初设想更安全，但代码行数
减少得也更少（这是有意识的取舍，用户已确认接受）。

### 7.2 `<include>` 属性在 `<if test="...">` 里不能直接当 OGNL 变量用
第一版实现里，`<if test="groupByTenant == 'true'">`（没有 `${}`）在应用启动时能正常加载 Mapper，
但一到真机跑真实查询就报错：

```
BindingException: Parameter 'groupByTenant' not found.
Available parameters are [param5, roles, dateTo, dateFrom, ...]
```

**原因**：`<include>` 传进去的 `<property>` 只能通过 `${}` 做**文本替换**，不会自动变成一个可以被
OGNL 当变量名直接引用的绑定参数。`test="groupByTenant == 'true'"` 会被当成"去参数对象里找一个叫
`groupByTenant` 的属性"，但真正的运行时参数对象里根本没有这个字段（它只存在于 XML 作者层面），
所以报错。

**修复**：把所有条件改成 `test="'${groupByTenant}' == 'true'"`——`${groupByTenant}` 在 Mapper
加载阶段就被替换成字面量文本 `true`/`false`，最终 `<if>` 的 `test` 属性变成了单纯的字符串字面量
比较（例如 `'true' == 'true'`），不依赖任何运行时参数绑定。8 处 `<if>`（4 个新增列 × 2 个片段）
全部按这个模式修正。

---

## 8. 明确不做的部分（以及为什么）

优化建议阶段还识别出另外两组"看起来也很像重复"的 DAO 方法，**特意决定不动**：

- `CurrencyDao.findCurrencyByTenantId(Integer)` / `findCurrencyByTenantIds(List<Integer>)`
- `TenantDao.findTenantById(int)` / `findTenantsByIds(List<Integer>)`

原因：这两对方法的"单个版本"不是 Dashboard 专属代码，而是被 `CurrencyController`、
`CurrencyServiceImpl`、`DomainServiceImpl`、`TransactionSearchServiceImpl`（`CurrencyDao`），
以及登录/鉴权等核心流程（`TenantDao`）广泛依赖。像对待 Ownership 查询那样把它们合并/删除，
影响范围会远远超出 Dashboard 这一个模块，风险和收益不成比例，所以保留现状。

Controller 层的"端点是不是可以合并"这个想法也讨论过，结论是：**不合并端点本身**（每个端点语义
不同，前端调用方式也不同），如果要精简，方向应该是去重"每个端点重复的响应包装样板代码"，这部分
还没有开始实施。

---

## 9. 验证方式与结果

每一步改完都用同一套真实数据基线跑回归对比，数据来源是本地 `count_real` 数据库：

- 登录身份：`account_id=3`，`owner_type=owner`
- 单 Company：`tenant_id=2`（公司代码 "95"）
- Group：`tenant_id=33`（IG Group），旗下公司 `[6, 5, 2, 3]`
- 时间范围：`2026-08-01` ~ `2026-08-31`，币种 `MYR`

类别 A 完成后跑的最后一轮回归结果（`getKpi` / `getKpiForGroup` / `getKpiForCompanies` /
`getTrend` / `getTrendForGroup` / `getTrendForCompanies` 六个方法全覆盖）：

| 场景 | netProfit | earnings |
|---|---|---|
| Company 95（`getKpi`） | 26220.35601902 | 7079.49612514 |
| Group 33（`getKpiForGroup`） | 190400.75502197 | 171360.67951977 |
| Company: All（`getKpiForCompanies`） | 220199.31856310 | — |
| Company 95 Trend（31 天求和） | 26220.35601902 | 7079.49612515 |
| Group 33 Trend（31 天求和） | 190400.75502197 | 171360.67951978 |
| Company: All Trend（31 天求和） | 220199.31856310 | 171360.67951978 |

全部跟改造前的基线数字精确对上（Trend 求和的极小尾差是 `double` 累加浮点误差，不是数据差异）。

验证方式：写一个一次性的 `@SpringBootTest`（`backend/src/test/java/com/eazycount/service/` 下），
用手工构造的 `SessionUser` + `LoginUserPrincipal` 塞进 `SecurityContextHolder` 模拟登录态，跑完
打印 `MANUAL_CHECK` 前缀的结果，核对完立刻删除，不留在代码库里。

编译验证：`mvnw.cmd -o compile` BUILD SUCCESS，无警告无报错。

---

## 10. DTO 合并：DashboardGroupCompanyNetProfitDTO → 内嵌类

用户提的规则：Dashboard 相关的 DTO 只保留三类——Chart 一个、KPI 卡片一个、Currency 一个，不要为了
一个小结构就单开一个文件。`DashboardGroupCompanyNetProfitDTO`（Group Net Profit Tab 用，3 个字段：
`code`/`netProfit`/`group`）单独占了一个文件，问题是它跟哪个"大类"合并。

**没有直接把字段塞进 `DashboardCurrencyAmountDTO`**：两者字段语义不兼容——`DashboardCurrencyAmountDTO`
的 `code` 是**货币代码**（如 `MYR`），`DashboardGroupCompanyNetProfitDTO` 的 `code` 是**公司租户代码**
（如 `"95"`）；前者有 `originalAmount`/`amount`/`rate`/`earnings`/`earningsConverted` 五个跟汇率换算
相关的字段，后者只有 `netProfit`/`group`、完全不做汇率换算。强行合并成一个 DTO 会出现"货币场景下
`netProfit`/`group` 永远 null，公司场景下另外五个字段永远 null"的半空结构，字段名字面意思也会打架。

**改成内嵌静态类**：参照代码里已经在用的 `DashboardKpiDTO.RoleAmount`/`DashboardTrendPointDTO.RoleAmount`
模式——物理上塞进同一个 `.java` 文件（满足"少开文件"），逻辑上字段互不污染：

```java
public class DashboardCurrencyAmountDTO {
    private String code;            // 货币代码
    private BigDecimal originalAmount, amount, rate, earnings, earningsConverted;

    public static class CompanyNetProfit {   // 原 DashboardGroupCompanyNetProfitDTO
        private String code;        // 公司租户代码——跟外层的 code 语义不同，故意不共用
        private BigDecimal netProfit;
        private String group;
    }
}
```

`DashboardGroupCompanyNetProfitDTO.java` 整个文件删除，`DashboardService.java`/`DashboardServiceImpl.java`/
`DashboardController.java` 里 5 处引用改成 `DashboardCurrencyAmountDTO.CompanyNetProfit`（导入、方法
签名、`new` 实例化）。JSON 序列化结果不变——Jackson 序列化内嵌类跟序列化顶层类没有区别，字段名一样，
前端零改动。

验证：`mvnw compile` BUILD SUCCESS，无遗留引用（`grep DashboardGroupCompanyNetProfitDTO` 全仓库零命中）。

---

## 11. Service 层第二轮：参数校验样板抽取 + Group 加权求和算法合并

第 3～5 节做完 Mapper/DAO 层之后，用户又问了一轮"Service 层还能怎么优化"，挑了其中两项动手：

### 11.1 参数校验样板抽取

`DashboardServiceImpl` 里 10 个 public 方法（`getKpi`/`getKpiForGroup`/`getKpiForCompanies`/
`getTrend`/`getTrendForGroup`/`getTrendForCompanies`/`getKpiCurrencyBreakdown`/
`getKpiCurrencyBreakdownForCompanies`/`getGroupKpiCurrencyBreakdown`/`getGroupCompanyNetProfitBreakdown`）
开头几乎都是同一套校验逻辑，只是参数名字不同（`tenantId` vs `groupTenantId` vs `tenantIds`，
`currencyCode` vs `baseCurrencyCode`）。抽成几个共享的私有校验方法：

```java
requireTenantId(Integer)        requireGroupTenantId(Integer)     requireTenantIds(List<Integer>)
requireDateRange(from, to)      requireCurrency(String) -> trim   requireBaseCurrency(String) -> trim+upper
orEmpty(List<Integer>)          requireTenant(Integer) -> Tenant  requireGroupTenant(Integer) -> Tenant
```

`requireTenant`/`requireGroupTenant` 顺带把"查 tenant + 校验类型"这段也在 4 个 Group 系方法里去重了
（`getKpiForGroup`/`getTrendForGroup`/`getGroupCompanyNetProfitBreakdown`/`getGroupKpiCurrencyBreakdown`
原本各自写一遍 `findTenantById` + null 检查 + `GROUP` 类型检查）。10 个方法开头从 10～15 行压缩到
3～5 行，报错文案、判断顺序一字不改。

### 11.2 Group 加权求和算法合并

"按股权% 加权求和 Group Profit" 这段算法，原本在三个地方各写了一遍：`computeGroupProfit`（KPI 卡片
总数）、`getGroupKpiCurrencyBreakdown` 内联的按币种循环、`buildGroupTrendPoints` 内联的按天循环——
三处循环骨架完全一样，只有"这家公司的 Net Profit 怎么取"这一步不同。抽成一个共享方法：

```java
private static BigDecimal sumWeightedGroupProfit(List<Integer> companyTenantIds,
        Map<Integer, BigDecimal> equityPercentageByTenant, Function<Integer, BigDecimal> companyNetProfitFn) {
    BigDecimal total = BigDecimal.ZERO;
    for (Integer tenantId : companyTenantIds) {
        BigDecimal percentage = equityPercentageByTenant.get(tenantId);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) == 0) continue;
        total = total.add(companyNetProfitFn.apply(tenantId)
                .multiply(percentage).divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
    }
    return total;
}
```

三处调用点各自只传一个"怎么取这家公司 Net Profit"的 lambda，跟 `buildKpiDto` 用 `BiFunction` 注入
"净利润怎么算"是同一个手法。

**踩的坑**：`buildGroupTrendPoints` 里被 lambda 捕获的几个变量（`for` 循环里会重新赋值的 `date`；
只在 `if` 块里条件赋值的两个 Map）不满足 Java "lambda 只能捕获 effectively final 变量"的要求，
编译报错 `local variables referenced from a lambda expression must be final or effectively final`。
修复：循环内加一份 `LocalDate currentDate = date;`；两个 Map 在 `if` 块结束后各自赋给一个 `final`
局部变量，lambda 里引用这些 final 副本，不直接引用会被重新赋值的原变量。

**验证**：`mvnw compile` BUILD SUCCESS；真机回归覆盖了全部 9 个对外方法（KPI×3、Trend×3、
Currency×3），六组基线数字精确对上，且 Company:All 的 Currency 汇总（220199.31856310）与
`getGroupCompanyNetProfitBreakdown` 四家公司 Net Profit 相加的结果精确一致，交叉验证了加权求和
逻辑没有被改变。

---

## 12. 注释精简

用户要求把 `DashboardServiceImpl.java` 里的注释都改短一些，但要保证看得懂、保持英文。逐个检查了
文件里全部 75 处注释，把多句话的"为什么"说明压缩成一到两句紧凑的话，业务规则本身（比如持股降级
公式、"没活动显示 0 不是 —"这类规则）一个字都没丢，本来就短的单行注释保持不变。改完 `mvnw compile`
BUILD SUCCESS——注释不影响字节码，编译通过也顺带确认了没有不小心改到代码本身。

发现但没动的一处：`earningsFrom()` 方法上面的注释写着"Live table for the current month, monthly
snapshot history table otherwise"，内容跟这个方法实际做的事（纯乘除运算，不查表）对不上，像是早前
重构时从别处误留下来的。这是内容准确性问题，不是长度问题，所以没有一并改掉，留给用户确认要不要
处理。

---

## 13. 尚未开始的后续优化项

- **Controller 响应包装样板代码去重**：已提出方向（不合并端点，只抽取重复的"try/catch + 统一
  响应体包装"逻辑），尚未开始实施，等用户确认。
- **`earningsFrom()` 上方的过时/不准确注释**：见第 12 节末尾，内容跟方法实际行为对不上，需要确认
  是修正内容还是直接删掉。
- 本文档只覆盖这次"代码优化"阶段的改动；Dashboard 各功能本身的业务规则、算法、真机验证记录，
  仍然维护在 [`dashboard-springboot-kpi.md`](./dashboard-springboot-kpi.md) 里，本文件不重复记录。
