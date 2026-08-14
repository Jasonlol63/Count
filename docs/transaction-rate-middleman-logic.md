# RATE Middle-Man / Rate-Mul / Platform Fee（Spring Boot 现行实现）

> 范围：`POST /api/transaction/submit`（`transactionType=RATE`）在 Spring Boot 后端的完整实现。
> 与 `Count-frontend` 的 payload 映射见 [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md)。
>
> 本文档描述的是**当前仓库（Spring Boot）**的简化模型，**不是** legacy PHP（`transaction_entry` / `transactions_rate_details`）那一套。两边字段命名相似但语义不同，改代码或读旧参考文档时不要混用。

---

## 1. 模型概述

RATE 是「两条 Cr/Dr 腿 + 可选 Middle-Man Win/Loss 腿」的组合，一次提交落库：

1. **leg1**：第一币种，`leg1ToAccountId` / `leg1FromAccountId` 两个账户之间的一笔 Cr/Dr，金额 = `leg1Amount`；
2. **leg2**：第二币种，`leg2ToAccountId` / `leg2FromAccountId` 之间的一笔 Cr/Dr，金额 = `leg2Amount`；
3. **Middle-Man（可选）**：账户 + Rate-Mul 乘数/除数 和/或 Fee 和/或 Platform Fee 的任意组合，产生 0～2 笔 Win/Loss 分录；
4. **`transactions_rate`**：一行头表，记录 FX 元数据（汇率、双边币种/金额、Middle-Man 原始输入），用 `rate_group_id` 把 leg1/leg2 串起来。

RATE 直接落 `APPROVED`，不走待审批。

**leg1 与 leg2 都是必填**——这是跟 PHP legacy 模型最大的差异：PHP 那边"第二组账户"是可选的（不填就只有 leg1 一笔账，`transactions_rate` 里的第二币种信息纯展示）；Spring Boot 这边 `transactions_rate.leg2_transaction_id` 是 `NOT NULL` 外键，`submitRate()` 对 leg1/leg2 都无条件调用 `requireFromToAccounts(...)`。前端已经在 `useTransactionForm.js` 里把"第二组账户"改成强制必填以对齐这个约束（详见前端文档）。

leg2 是**单一对称金额**（一个 `amount` 字段，Cr/Dr 双边共用），不支持 PHP 那种"Transfer To 侧和 From 侧金额不同"的写法——这也是简化点之一。

---

## 2. 入口与主要类

| 层级 | 文件 | 职责 |
|------|------|------|
| Controller | `controller/TransactionController.java` | `POST /api/transaction/submit` |
| DTO | `dto/TransactionSubmitDTO.java` | 请求/响应共用一个 DTO |
| Service | `service/impl/TransactionSubmitServiceImpl.java` | `submitRate()` / `resolveMiddleman()` 等 |
| Rate-Mul 算法 | `util/RateMulCalculator.java` | 解析 Rate-Mul 输入、算佣金 |
| 金额精度 | `util/TransactionMoneyFormat.java` | 见 [`transaction-amount-precision.md`](./transaction-amount-precision.md) |
| Entity | `entity/TransactionRate.java` | 映射 `transactions_rate` |
| DAO | `dao/TransactionRateDao.java` + `mybatis/TransactionRateMapper.xml` | 头表 insert/delete |
| Schema | `sql/schema.sql`（`transactions_rate` 定义）+ `sql/migrate_rate_platform_fee_and_ratemul.sql`（增量迁移） | |

---

## 3. `TransactionSubmitDTO` 的 RATE 相关字段

```text
leg1ToAccountId / leg1FromAccountId / leg1CurrencyId / leg1CurrencyCode / leg1Amount
leg2ToAccountId / leg2FromAccountId / leg2CurrencyId / leg2CurrencyCode / leg2Amount
exchangeRate            数值化汇率（正数，≤8 位小数）
rateExpression           FX 原始文本，如 "/1.5"、"3.15" —— 供 Rate-Mul 判断 FX 是除法还是乘法写法用

middlemanAccountId
middlemanRate            legacy 裸乘数字段（BigDecimal），仅在 middlemanRateExpression 缺省时兜底
middlemanRateExpression  Rate-Mul 原始文本，如 "/1.55"（除法模式）或 "2.93"（乘法模式/新汇率）
middlemanAmount          Service Fee 面值，第二（leg2）币种，不换汇
platformFeeAmount        Platform Fee 面值，第二（leg2）币种，恒正数，恒代表减法
```

三个 Middle-Man 输入项（Rate-Mul / Fee / Platform Fee）**互相独立、任意子集都可以单独存在**——账户是否必填只取决于三者是否有任一被填写。

---

## 4. `resolveMiddleman()` 决策树

1. 三项都没填 → 无 Middle-Man，返回 `null`，走普通 `validateRateAmounts`（`leg2Amount` 必须精确等于 `leg1Amount × exchangeRate`）。
2. 选了账户但三项都没填 → 报错「Middle-Man requires rate multiplier, fee, and/or platform fee」。
3. 填了任一项但没选账户 → 报错「Middle-Man account is required when rate multiplier, fee, or platform fee is set」。
4. 否则：
   - **Rate-Mul**：`RateMulCalculator.parseMiddlemanRateInput()` 解析 `middlemanRateExpression`（缺省时用 `middlemanRate` 的字符串形式兜底），解析出的除数/乘数还要过 `TransactionMoneyFormat.requireMaxScale(..., 8)`（跟其他 RATE 数值字段同一条规则）。再调 `RateMulCalculator.computeCommission(...)` 算出佣金，**可能为负**（中间人倒贴）。
   - **Fee**：`middlemanAmount` 走 `parsePositiveRateAmount`（必须 >0，≤8 位小数），**不再乘汇率**——直接就是 leg2 币种的面值。
   - **Platform Fee**：`platformFeeAmount` 同样必须 >0，≤8 位小数。
   - `feeNet = Fee − PlatformFee`（可能 ≤0）。
5. **只有 >0 的部分才会真正插入分录**：`ratePortion = rateMulCommission > 0 ? rateMulCommission : null`；`feePortion = feeNet > 0 ? feeNet : null`。倒贴（Rate-Mul 为负）或 PT 把 Fee 吃光（feeNet ≤0）**都不报错**，只是那一笔 Win/Loss 分录不写；`transactions_rate` 头表仍然记录用户的原始输入供审计。
6. `total = (ratePortion 或 0) + (feePortion 或 0)` 必须 `< grossTo`，否则报错「Middle-Man total must be less than leg2 gross amount」。
7. 有 Middle-Man 时，`leg2Amount` 必须精确等于 `grossTo − total`（`RATE_AMOUNT_TOLERANCE = 1e-8`），否则报错「Leg2 amount must equal (leg1 × exchange rate) − middleman total」——**这是前端拼 `leg2Amount` 时必须复刻的公式**，见前端文档。

---

## 5. Rate-Mul 算法（`RateMulCalculator`）

三种分支，跟前端 `transactionSubmitHelpers.js` 的 `computeRateMulCommission` 完全对齐：

| Rate-Mul 输入 | FX Rate 写法 | 公式 |
|---|---|---|
| `/newDivisor`（除法） | 必须也是 `/divisor` 才生效 | `from/newDivisor − from/divisor` |
| 纯正数（乘法/点数） | FX 是 `/divisor` | 点数直接用：`mul × 1000` |
| 纯正数（乘法/新汇率） | FX 是普通乘法写法 | 带符号做差：`(原汇率 − mul) × fromAmount`（结果可为负，即倒贴） |
| 模式与 FX 写法不匹配 | — | 佣金 = 0（忽略，不报错） |

纯负数输入一律无效（`ParsedRate.valid = false`），提交时报错。中间计算用 20 位小数精度（`RoundingMode.HALF_UP`），最终结果统一交给 `TransactionMoneyFormat.normalizeComputedRate`（超过 8 位才 HALF_UP 到 8 位）。

**已知限制**：DIVIDE 模式和"FX 是除法写法时的点数模式"都需要 `rateExpression`（FX 原始文本）才能判断 FX 是不是 `/divisor` 写法——如果前端没传这个字段，这两种模式会退化成 0（不报错，只是不生效）。"FX 是乘法写法时的新汇率做差"模式不受影响，因为只需要数值化的 `exchangeRate`。

---

## 6. Fee / Platform Fee 语义（2026-08 起）

| 项目 | 语义 |
|---|---|
| Fee（`middlemanAmount`） | **第二（leg2）币种面值，不换汇**。History remark（`CHARGE {leg2币种} {fee} SERVICE FEES`）也用 leg2 币种。 |
| Platform Fee（`platformFeeAmount`） | 第二币种面值，恒正数，**恒代表减法**：`feeNet = Fee − PlatformFee`。**不产生独立的分录行**——只影响 Fee 那一笔 Win/Loss 的最终金额，以及 `transactions_rate.platform_fee_amount` 这个头表字段的记录值。 |

这跟 legacy PHP 模型（`RATE_PLATFORM_FEE` 是 `transaction_entry.entry_type` 的一个独立枚举值，会在 Select From 账户上单独插入一笔 `+PT` 的 Cr/Dr）**完全不同**——PHP 是"多一行分录"，我们这边是"多一个头表字段、不产生分录"。这是本仓库刻意做的简化（不引入 `transaction_entry` 统一分录表），不是遗漏。

**Fee 口径变更历史**：这个字段以前是"第一币种输入，落 Win/Loss 前要 `× exchangeRate`"（跟 legacy PHP 旧版一致）。2026-08 改成第二币种面值不换汇，`TransactionSubmitServiceImpl.formatServiceFeeRemark()` 的调用方也从 `leg1Ccy` 改成了 `leg2Ccy`。

---

## 7. 落库分录

| 分录 | 账户 | 金额符号 | 说明 |
|---|---|---|---|
| leg1 | `leg1ToAccountId` / `leg1FromAccountId` | Cr/Dr（To −，From +，同 PAYMENT） | 恒写 |
| leg2 | `leg2ToAccountId` / `leg2FromAccountId` | Cr/Dr，同上 | 恒写，金额 = `grossTo − ratePortion(若>0) − feePortion(若>0)` |
| Rate 分录 | To=`leg2.toAccountId`，From=`middleman.accountId` | Win/Loss（From=middleman +，To=leg2 payer −，同 PROFIT 符号） | 仅 `ratePortion != null`（即 Rate-Mul 佣金 >0）才写 |
| Fee 分录 | To=`middleman.accountId`，From=null（无对手方） | Win/Loss，middleman 单边 + | 仅 `feePortion != null`（即 `Fee − PT > 0`）才写 |

两笔 Middle-Man 分录都用第二币种（`leg2.currency`）。`transactions_rate` 头表**始终**记录 `middleman_rate` / `middleman_rate_expression` / `middleman_amount`（Fee 原始值）/ `platform_fee_amount`（PT 原始值），跟是否真的写了分录无关——即使某次提交因为 Rate-Mul 倒贴或 PT 吃光 Fee 导致两笔分录都没写，头表依然留痕。

---

## 8. Description 文案

沿用 [`transaction-description-rules.md`](./transaction-description-rules.md) 的规则，Middle-Man 那部分本次改了 rate token 的生成方式：

```text
Fee:            MARKUP X {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 除法模式：  MARKUP /{divisor} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 乘法模式：  MARKUP x{value} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
```

以前是直接打印裸乘数（`{middlemanRate}`），现在按 `ParsedRate.mode()` 加 `/` 或 `x` 前缀，跟前端 `middlemanRateDesc` 的展示风格一致，避免"除以 1.55"和"乘以 1.55"在历史记录里分不清。

---

## 9. Schema：`transactions_rate`

| 列 | 类型 | 说明 |
|---|---|---|
| `exchange_rate` | `DECIMAL(18,8)` | 数值化汇率 |
| `rate_expression` | `VARCHAR(64)` | FX 原始文本 |
| `middleman_account_id` | `INT UNSIGNED` | 可空 |
| `middleman_rate` | `DECIMAL(18,8)` | 除法模式存除数，乘法模式存乘数/新汇率原值 |
| `middleman_rate_expression` | `VARCHAR(32)`（**新增**） | Rate-Mul 原始文本，如 `/1.55` |
| `middleman_amount` | `DECIMAL(25,8)` | Service Fee 面值，**leg2 币种**（列注释已同步更新，语义变更见第 6 节） |
| `platform_fee_amount` | `DECIMAL(25,8)`（**新增**） | Platform Fee 面值，leg2 币种 |

迁移文件：[`sql/migrate_rate_platform_fee_and_ratemul.sql`](../backend/src/main/resources/sql/migrate_rate_platform_fee_and_ratemul.sql)（幂等性：只能在这两列不存在时执行一次）。全新装库直接看 `sql/schema.sql`。

不在这套 schema 里：legacy PHP 的 `transactions_rate_details` / `transaction_entry`（沿用 `migrate_rate_tables_optimized.sql` 就定下的简化）。

---

## 10. 已知限制 / 后续

1. **DIVIDE 模式依赖前端传 `rateExpression`**——见第 5 节，前端已经在 `buildRatePayload` 里加了这个字段（`rate_expression`），只要走新版前端就没问题；如果有别的调用方（比如未来的 mobile）没传这个字段，这两种模式会静默退化成 0。
2. **精度上限是 8 位小数**（`RATE_AMOUNT_SCALE=8`），沿用本仓库既有约定；`count168test` 参考文档里 2026-08 之后写的是"6 位截断"，那是 legacy PHP 的现行规则，不适用于本仓库。
3. **leg2 不支持两侧金额不同**——PHP 模型里"有 Rate-Mul 乘数时 Transfer 两侧金额可以不等"这个场景，在本仓库里被简化成"只有一个对称金额"，Rate-Mul/Fee 的净扣减已经算进这一个金额里了。

---

## 11. 相关文件

**Backend**

- `service/impl/TransactionSubmitServiceImpl.java`（`submitRate` / `resolveMiddleman` / `formatMiddlemanMarkupDescription` / `formatServiceFeeRemark`）
- `util/RateMulCalculator.java`
- `util/TransactionMoneyFormat.java`
- `dto/TransactionSubmitDTO.java`
- `entity/TransactionRate.java`
- `dao/TransactionRateDao.java` + `mybatis/TransactionRateMapper.xml`
- `sql/schema.sql` / `sql/migrate_rate_platform_fee_and_ratemul.sql`

**Related docs**

- [transaction-amount-precision.md](./transaction-amount-precision.md)
- [transaction-description-rules.md](./transaction-description-rules.md)
- [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md) —— 前端 payload 映射

---

## 12. 数字示例

假设：`leg1Amount=1000 MYR`，`exchangeRate=4`（乘法写法，非除法），`gross=4000`；Middle-Man 账户已选，`Rate-Mul="3.9"`（新汇率模式，比原汇率小 0.1），`Fee=50`（第二币种面值），`PlatformFee=20`。

```text
rateMulCommission = (4 − 3.9) × 1000 = 100        → ratePortion = 100（>0，写分录）
feeNet             = 50 − 20 = 30                  → feePortion = 30（>0，写分录）
total              = 100 + 30 = 130
leg2Amount 必须    = 4000 − 130 = 3870

写入：
  leg1: MYR 1000（两个第一组账户之间）
  leg2: 第二币种 3870（两个第二组账户之间）
  Rate 分录: 第二币种 100，From=middleman(+)，To=leg2.toAccount(−)
  Fee 分录:  第二币种 30，To=middleman(+)，From=null

transactions_rate 头表：
  middleman_rate = 3.9, middleman_rate_expression = "3.9"
  middleman_amount = 50, platform_fee_amount = 20
```
