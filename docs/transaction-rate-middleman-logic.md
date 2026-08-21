# RATE Middle-Man / Rate-Mul / Platform Fee（Spring Boot 现行实现）

> **本文件与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md#26-rate-middle-man--rate-mul--platform-feespring-boot-现行实现) 第 26 节内容同步维护**——RATE 相对复杂，单独拆出来方便查阅，但迁移文档里也保留了一份完整拷贝。**改这块逻辑时，两份文件都要同步更新**，避免内容分叉。

> 范围：`POST /api/transaction/submit`（`transactionType=RATE`）在 Spring Boot 后端的完整实现。
> 与 `Count-frontend` 的 payload 映射见 [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md)。
>
> 本文档描述的是**当前仓库（Spring Boot）**的简化模型，**不是** legacy PHP（`transaction_entry` / `transactions_rate_details`）那一套。两边字段命名相似但语义不同，改代码或读旧参考文档时不要混用。

---

## 1. 模型概述

RATE 是「两条 Cr/Dr 腿 + 可选 Middle-Man Win/Loss 腿」的组合，一次提交落库：

1. **leg1**：第一币种，`leg1ToAccountId` / `leg1FromAccountId` 两个账户之间的一笔 Cr/Dr，金额 = `leg1Amount`；
2. **leg2**：第二币种，`leg2ToAccountId` / `leg2FromAccountId` 之间的一笔 Cr/Dr，金额 = flat 毛额 `grossTo`（**不是** `leg2Amount`，见下方 2026-08 变更）；
3. **Middle-Man（可选）**：账户 + Rate-Mul 乘数/除数 和/或 Fee 和/或 Platform Fee 的任意组合，产生 0～3 笔 Win/Loss 分录（Rate / Fee / Platform Fee 各一笔）；
4. **`transactions_rate`**：一行头表，记录 FX 元数据（汇率、双边币种/金额、Middle-Man 原始输入），用 `rate_group_id` 把 leg1/leg2 串起来。

RATE 直接落 `APPROVED`，不走待审批。

**leg1 与 leg2 都是必填**——这是跟 PHP legacy 模型最大的差异：PHP 那边"第二组账户"是可选的（不填就只有 leg1 一笔账，`transactions_rate` 里的第二币种信息纯展示）；Spring Boot 这边 `transactions_rate.leg2_transaction_id` 是 `NOT NULL` 外键，`submitRate()` 对 leg1/leg2 都无条件调用 `requireFromToAccounts(...)`。前端已经在 `useTransactionForm.js` 里把"第二组账户"改成强制必填以对齐这个约束（详见前端文档）。

leg2 是**单一对称金额**（一个 `amount` 字段，Cr/Dr 双边共用），不支持 PHP 那种"Transfer To 侧和 From 侧金额不同"的写法——这也是简化点之一。

**2026-08 记账方式变更**：leg2 现在**恒等于 `amountFrom × exchangeRate` 的 flat 毛额**，服务端自己算，不再信任前端传来的 `leg2Amount`（该字段仍会被接收但不参与记账/校验，纯前端展示用）。Rate-Mul、Fee、Platform Fee 造成的所有扣减，一律通过**额外插入**的 Win/Loss 分录去冲抵 `leg2.fromAccountId()`（"from account"），不会改动 leg2 本身的金额——这样 leg2 的 To 账户永远拿到干净的毛额，From 账户的净额则由明细分录累加得出，审计明细不会被"存净额"吞掉。详见第 6、7、10、11 节。

---

## 2. 入口与主要类

| 层级 | 文件 | 职责 |
|------|------|------|
| Controller | `controller/TransactionController.java` | `POST /api/transaction/submit` |
| DTO | `dto/TransactionSubmitDTO.java` | 请求/响应共用一个 DTO |
| Service（提交） | `service/impl/TransactionSubmitServiceImpl.java` | `submitRate()` / `resolveMiddleman()` 等 |
| Service（Payment History） | `service/impl/TransactionHistoryServiceImpl.java` | `mergeRateMiddlemanDeductionsIntoMainLeg()` 等，见第 10 节 |
| Service（CONTRA 汇总） | `service/impl/TransactionSearchServiceImpl.java` | `buildDomainPaymentSearchSlice()` 等，见第 11 节 |
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
platformFeeAmount        Platform Fee 面值，第二（leg2）币种，恒正数（用法见第 6、7 节）
```

三个 Middle-Man 输入项（Rate-Mul / Fee / Platform Fee）**互相独立、任意子集都可以单独存在**——账户是否必填只取决于三者是否有任一被填写。

---

## 4. `resolveMiddleman()` 决策树

1. 三项都没填 → 无 Middle-Man，返回 `null`；leg2 照样记 `grossTo`（`leg1Amount × exchangeRate`），跟有没有 Middle-Man 无关（见第 7 点，`validateRateAmounts` 已删除）。
2. 选了账户但三项都没填 → 报错「Middle-Man requires rate multiplier, fee, and/or platform fee」。
3. 填了任一项但没选账户 → 报错「Middle-Man account is required when rate multiplier, fee, or platform fee is set」。
4. 否则：
   - **Rate-Mul**：`RateMulCalculator.parseMiddlemanRateInput()` 解析 `middlemanRateExpression`（缺省时用 `middlemanRate` 的字符串形式兜底），解析出的除数/乘数还要过 `TransactionMoneyFormat.requireMaxScale(..., 8)`（跟其他 RATE 数值字段同一条规则）。再调 `RateMulCalculator.computeCommission(...)` 算出佣金，**可能为负**（中间人倒贴）。
   - **Fee**：`middlemanAmount` 走 `parsePositiveRateAmount`（必须 >0，≤8 位小数），**不再乘汇率**——直接就是 leg2 币种的面值。
   - **Platform Fee**：`platformFeeAmount` 同样必须 >0，≤8 位小数。
   - `feeNet = Fee − PlatformFee`（可能 ≤0）。
5. **只有 >0 的部分才会真正插入分录**：`ratePortion = rateMulCommission > 0 ? rateMulCommission : null`；`feePortion = feeNet > 0 ? feeNet : null`。倒贴（Rate-Mul 为负）或 PT 把 Fee 吃光（feeNet ≤0）**都不报错**，只是那一笔 Win/Loss 分录不写；`transactions_rate` 头表仍然记录用户的原始输入供审计。
6. `total = (ratePortion 或 0) + (feePortion 或 0)` 必须 `< grossTo`，否则报错「Middle-Man total must be less than leg2 gross amount」。
7. ~~有 Middle-Man 时校验 `leg2Amount = grossTo − total`~~——**2026-08 起已移除**。leg2 现在恒记 `grossTo`（服务端算，不看前端传的 `leg2Amount`），不存在"净额是否对得上"这个校验了，`RATE_AMOUNT_TOLERANCE` 常量、`validateRateAmounts()` 方法也一并删除。

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

## 6. Fee / Platform Fee 语义（2026-08 起，含 Payment History 优化后的最新口径）

| 项目 | 语义 |
|---|---|
| Fee（`middlemanAmount`） | **第二（leg2）币种面值，不换汇**。落库分录金额是 `feePortion = Fee − PlatformFee`（净额，见下）——**middleman 实收的是净额**，platform 抽走的那部分不会算进 middleman 的收入。 |
| Platform Fee（`platformFeeAmount`） | 第二币种面值，恒正数。**有自己独立的分录行**（`CHARGE {ccy} {amt} PLATFORM FEE`），单边只记在 `leg2.fromAccountId()` 上，没有对手方（不给任何账户 +）。 |

**这跟本文档更早版本描述的"Platform Fee 不产生独立分录行，只影响 Fee 净额"已经不一样了**——2026-08 中旬这版继续迭代，改成了 Platform Fee 也要有自己看得见的一行记录（原因：需要在 Payment History 里单独显示 `CHARGE ... PLATFORM FEE` 这条，而不是只靠 Fee 净额隐性体现）。`transactions_rate.platform_fee_amount` 头表字段依然保留（记录原始输入值，Payment History 合并展示时要用它把 Fee 分录的净额"还原"成满额，见第 10 节）。

**Fee 口径变更历史**：这个字段以前是"第一币种输入，落 Win/Loss 前要 `× exchangeRate`"（跟 legacy PHP 旧版一致）。2026-08 改成第二币种面值不换汇。

**Service Fee remark 已移除（2026-08 中旬）**：以前 leg1（toAccount1）会写一条 `CHARGE {leg2币种} {fee} SERVICE FEES` 的 remark（`TransactionSubmitServiceImpl.formatServiceFeeRemark()`），是旧算法里"Service Fee 会自动从 leg1 to account 扣"这件事的留痕。新算法不会再自动扣这笔——Service Fee 已经通过 Fee 分录（第 7 节）体现在 leg2 from account 上，用户填 Fee 只是为了让金额、middleman 收入算准，跟 leg1 无关，这条 remark 因此失去意义，已删除（`formatServiceFeeRemark()` 方法整个移掉，`leg1Txn` 的 remark 恢复成用户自己填的 `remark` 原样）。

---

## 7. 落库分录

一次 RATE 提交最多写 5 笔 `transactions`，全部共用同一个 `rate_group_id`：

| 分录 | 账户（account_id / from_account_id） | 金额 | 说明 |
|---|---|---|---|
| leg1 | `leg1ToAccountId` / `leg1FromAccountId` | `leg1Amount` | Cr/Dr（To −，From +，同 PAYMENT），恒写 |
| leg2 | `leg2ToAccountId` / `leg2FromAccountId` | `grossTo`（**flat 毛额**，`amountFrom × exchangeRate`） | Cr/Dr，恒写。**不再是"毛额减 Middle-Man"的净额**——leg2 永远是干净的毛额，扣减全部靠下面几笔额外分录冲抵 `leg2.fromAccountId()` |
| Rate 分录 | To=`leg2.fromAccountId()`，From=`middleman.accountId` | `ratePortion` | Win/Loss（From=middleman +，To=leg2 from account −）。仅 `ratePortion != null`（Rate-Mul 佣金 >0）才写 |
| Fee 分录 | To=`leg2.fromAccountId()`，From=`middleman.accountId` | `feePortion = Fee − PlatformFee` | Win/Loss，跟 Rate 分录同样是双边（middleman +，leg2 from account −）。仅 `feePortion != null`（即 `Fee − PT > 0`）才写 |
| Platform Fee 分录 | To=`leg2.fromAccountId()`，From=`NULL`（无对手方） | `platformFeeAmount` | Win/Loss，单边，只记在 leg2 from account 上。仅 `platformFeeAmount != null` 才写 |

**跟改动前的关键差异**：
- Rate 分录以前的对手方是 `leg2.toAccountId`（"leg2 payer"），现在改成 `leg2.fromAccountId()`——扣减方从"付款方"转移到"收款方（from account）"，因为这就是你实际要追踪"这个账户还欠多少 / 净拿到多少"的那个账户。
- Fee 分录以前是**单边**只记 middleman +（`from_account_id = NULL`），现在改成跟 Rate 分录一样的**双边**结构，才能同时体现"middleman 收入"和"leg2 from account 被扣了多少"两件事。
- Platform Fee 以前**没有**自己的分录，现在**有**了，且是单边（模式跟旧版的 Fee 分录一样：只给一方记账，没有对手方）。

三笔 Middle-Man 分录都用第二币种（`leg2.currency`）。`transactions_rate` 头表**始终**记录 `middleman_rate` / `middleman_rate_expression` / `middleman_amount`（Fee 原始值）/ `platform_fee_amount`（PT 原始值），跟是否真的写了分录无关——即使某次提交因为 Rate-Mul 倒贴或 PT 吃光 Fee 导致对应分录没写，头表依然留痕。

---

## 8. Description 文案

沿用 [`transaction-description-rules.md`](./transaction-description-rules.md) 的规则，Middle-Man 那部分本次改了 rate token 的生成方式：

```text
Fee:            MARKUP X {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 除法模式：  MARKUP /{divisor} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Rate 乘法模式：  MARKUP x{value} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}
Platform Fee：  CHARGE {ccy2} {amount} PLATFORM FEE
```

以前是直接打印裸乘数（`{middlemanRate}`），现在按 `ParsedRate.mode()` 加 `/` 或 `x` 前缀，跟前端 `middlemanRateDesc` 的展示风格一致，避免"除以 1.55"和"乘以 1.55"在历史记录里分不清。

Platform Fee 走的是完全独立的 `formatPlatformFeeDescription()`，**不是** `formatMiddlemanMarkupDescription()`——不带 `MARKUP` 前缀、不带 `FROM {account}`，就是固定的 `CHARGE {ccy} {amt} PLATFORM FEE`。查询层用这个固定文案（`LIKE 'CHARGE % PLATFORM FEE'`）识别这一类分录，详见第 10、11 节。

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
| `platform_fee_amount` | `DECIMAL(25,8)`（**新增**） | Platform Fee 面值，leg2 币种。**2026-08 中旬起也是独立分录（第 7 节）的落库金额来源**，不再只是"影响 Fee 净额的头表字段" |

迁移文件：[`sql/migrate_rate_platform_fee_and_ratemul.sql`](../backend/src/main/resources/sql/migrate_rate_platform_fee_and_ratemul.sql)（幂等性：只能在这两列不存在时执行一次）。全新装库直接看 `sql/schema.sql`。

不在这套 schema 里：legacy PHP 的 `transactions_rate_details` / `transaction_entry`（沿用 `migrate_rate_tables_optimized.sql` 就定下的简化）。

---

## 10. Payment History 展示层：把明细"合并"回 leg2 from account 的净额

`submitRate` 落库时是"毛额 + 多笔扣减分录"的明细结构（见第 7 节），但 leg2 from account 自己查 Payment History 时，不想看到一堆 `MARKUP ...` 明细行，而是想看到「一行净额（毛额扣掉 Rate-Mul + Service Fee） + 一行 Platform Fee」。这个"从明细合并成净额"的转换，全部发生在 **展示层**（`service/impl/TransactionHistoryServiceImpl.java`），不改落库数据。

**入口**：`buildDomainPaymentHistorySlice()` 在把 `findDomainPaymentHistoryLines` 查出来的每一行做完 `applyRateHistoryPresentation` 之后，调用 `mergeRateMiddlemanDeductionsIntoMainLeg(lines, accountId)`。

**做法**：
1. 找出这批记录里，`from_account_id == accountId` 且不是 Middle-Man 分录的那一行——这就是 leg2 主记录（因为只有 leg2 这一笔，被查询账户会出现在 `from_account_id` 位置）。
2. 遍历所有「双边 Middle-Man 分录」（`from_account_id != NULL` 且 `to_account_id == accountId`，也就是 Rate 分录和 Fee 分录），把它们的 `signedAmount` 直接加进 leg2 主记录，然后从最终列表里删掉这些行（不再单独显示）。
3. **Fee 分录要额外修正**：它落库时存的是 `feeNet = Fee − PlatformFee`（净额，见第 6 节），如果直接把这个净额并进 leg2，会导致 Platform Fee 被"合并进主记录"和"自己那行 `CHARGE ... PLATFORM FEE`"重复扣两次。所以判断这一行是不是 Fee 类（`isRateMiddlemanFeeKind`）之后，再把这个 rate_group 的 Platform Fee 原始金额减回去一次，把净额还原成满额——保证主记录只扣「Rate-Mul + Service Fee 满额」，Platform Fee 的影响完全交给它自己那一行。
4. Platform Fee 分录（单边，`from_account_id IS NULL`）不参与合并，原样保留成独立一行。

**列/ID PRODUCT 路由**（`toHistoryRow()`）：leg2 主记录本来就走 Cr/Dr（不是 Middle-Man 分录），合并后金额变了但列不变；Platform Fee 因为 `fromAccountId == null` 被识别出来，固定走 **Cr/Dr** 列、`product = "Fee"`；Rate 分录如果是 middleman 自己查（`fromAccountId == accountId`，即他在双边分录里的角色），则仍然走 **Win/Loss** 列、`product = "RATE"`，不受这次改动影响。

**mapper 配合**：`TransactionHistoryMapper.xml` 的 `rateMiddlemanKind` 分类 CASE 加了一条 `description LIKE 'CHARGE % PLATFORM FEE' → 'FEE'`，纯粹给 ID PRODUCT 列用，不影响任何金额/正负号计算。

**Rate-Mul token 展示（middleman 自己视角，2026-08 下旬新增）**：`formatRateMiddlemanMarkupDescription()` 拼 description 时，Rate 分录（非 Fee）的 rate token 不再直接印 middleman 输入的原始值，改用 `formatRateMiddlemanRateToken()` 按模式算出一个差值：
- **乘法模式**（FX 本身不是除法写法）：`原汇率 − middleman 输入`，例如 3 − 2.9 = `0.1`。
- **除法模式**（此时 FX 本身也必然是除法写法，否则佣金算出来是 0、根本不会写这笔分录）：`middleman 除数 − FX 除数`，例如 1.305 − 1.32 = `-0.015`。
- 两种都四舍五入到 6 位小数，位数不够按实际位数显示，不补零（复用 `formatRateHistoryDecimal`）。

这个改动**只影响 middleman 自己查 Payment History 时看到的文字**——落库的审计 description（`TransactionSubmitServiceImpl.formatMiddlemanMarkupDescription()`）还是印原始输入值，不受影响；leg2 from account 自己看到的合并视图也不受影响，因为 Rate/Fee 两行早就被合并进主记录了（见上文），根本不会显示这个 token。

数字示例见第 14 节。

---

## 11. CONTRA 汇总页：同一笔扣减也搬进 Cr/Dr

Transaction Payment 页面顶部那个「Account / B-F / Win-Loss / Cr-Dr / Balance」汇总表，走的是完全独立的一套纯 SQL 聚合（`service/impl/TransactionSearchServiceImpl.java` + `mybatis/TransactionSearchMapper.xml`），不经过上面第 10 节的 Java 合并逻辑，也不是"按查询账户视角挑一行行"的模式——它一次性把租户下所有账户按 `account_id + currency` 聚合成一行，Win/Loss 和 Cr/Dr 是两条完全分开跑的 SQL 分别产出再合并。

原本 `aggregateManualRateMiddlemanWinLoss` 有一条 UNION 分支，专门把「leg2 from account 的 `−amount`」（Rate-Mul + Fee 双边分录里 To 那一侧）算进 **Win/Loss**。这次改动把这条分支整个搬到新建的查询 `aggregateManualRateMiddlemanCrDr`，输出目标从 `winLossAmount` 换成 `crDrAmount`，在 `TransactionSearchServiceImpl.buildDomainPaymentSearchSlice()` 里跟 leg2 自己的毛额 Cr/Dr 行（`aggregateDomainPaymentCrDr`）合并加总。Middleman 自己那条 `+amount`（From/middleman 分支）留在原查询里，继续算 Win/Loss，不受影响。

**注意**：这里**没有**额外处理 Platform Fee 的加减——因为 Fee 分录落库金额本来就是 `Fee − PlatformFee`（净额），CONTRA 这边直接照单全收这个净额，数字天然就是对的（跟第 10 节 Payment History 里"先还原满额、再单独加 Platform Fee 一行"殊途同归，算出来的总数一致，只是 CONTRA 没有"单独一行"的概念，不需要拆开）。Platform Fee 单边分录本身在这套聚合里目前不会被任何分支捞到（既不是双边、也不匹配这个文件里 `rateMiddlemanFeeDescription` 的旧 pattern），CONTRA 汇总总数因此不含 Platform Fee 的字面数字，但因为它已经隐含在 Fee 净额里了，总数依然正确。

---

## 12. 已知限制 / 后续

1. **DIVIDE 模式依赖前端传 `rateExpression`**——见第 5 节，前端已经在 `buildRatePayload` 里加了这个字段（`rate_expression`），只要走新版前端就没问题；如果有别的调用方（比如未来的 mobile）没传这个字段，这两种模式会静默退化成 0。
2. **精度上限是 8 位小数**（`RATE_AMOUNT_SCALE=8`），沿用本仓库既有约定；`count168test` 参考文档里 2026-08 之后写的是"6 位截断"，那是 legacy PHP 的现行规则，不适用于本仓库。
3. **leg2 不支持两侧金额不同**——PHP 模型里"有 Rate-Mul 乘数时 Transfer 两侧金额可以不等"这个场景，在本仓库里被简化成"只有一个对称金额"；2026-08 起 leg2 恒记 flat 毛额，Rate-Mul/Fee/Platform Fee 的扣减全部体现在额外分录，不再体现在 leg2 本身。

---

## 13. 相关文件

**Backend**

- `service/impl/TransactionSubmitServiceImpl.java`（`submitRate` / `resolveMiddleman` / `formatMiddlemanMarkupDescription` / `formatPlatformFeeDescription`）
- `service/impl/TransactionHistoryServiceImpl.java`（`mergeRateMiddlemanDeductionsIntoMainLeg` / `toHistoryRow` / `applyRateMiddlemanHistoryPresentation` / `formatRateMiddlemanRateToken`）
- `service/impl/TransactionSearchServiceImpl.java`（`buildDomainPaymentSearchSlice`）
- `util/RateMulCalculator.java`
- `util/TransactionMoneyFormat.java`
- `dto/TransactionSubmitDTO.java`
- `entity/TransactionRate.java`
- `dao/TransactionRateDao.java` + `mybatis/TransactionRateMapper.xml`
- `dao/TransactionSearchDao.java` + `mybatis/TransactionSearchMapper.xml`（`aggregateManualRateMiddlemanWinLoss` / `aggregateManualRateMiddlemanCrDr`）
- `mybatis/TransactionHistoryMapper.xml`（`rateMiddlemanFeeDescription` / `rateMiddlemanKind`）
- `sql/schema.sql` / `sql/migrate_rate_platform_fee_and_ratemul.sql`

**Related docs**

- [transaction-amount-precision.md](./transaction-amount-precision.md)
- [transaction-description-rules.md](./transaction-description-rules.md)
- [`Count-frontend/docs/transaction-rate-springboot-submit.md`](../../Count-frontend/docs/transaction-rate-springboot-submit.md) —— 前端 payload 映射
- [frontend-springboot-migration.md](./frontend-springboot-migration.md#26-rate-middle-man--rate-mul--platform-feespring-boot-现行实现) —— 本文件内容的镜像拷贝（第 26 节）

---

## 14. 数字示例

假设：`leg1Amount=1000 SGD`，`exchangeRate=3`（乘法写法），`gross=3000 MYR`；Middle-Man 账户已选，`Rate-Mul="2.9"`（新汇率模式，比原汇率 3 小 0.1），`Fee=10`（第二币种面值），`PlatformFee=1.5`。账户：leg1 是 OK→OK2（SGD），leg2 是 OK2→OK（MYR，OK 是 `fromAccountId`），middleman 是 OK3。

```text
rateMulCommission = (3 − 2.9) × 1000 = 100          → ratePortion = 100（>0，写分录）
feeNet             = 10 − 1.5 = 8.5                  → feePortion = 8.5（>0，写分录）
total              = 100 + 8.5 = 108.5（< grossTo=3000，校验通过）

写入 5 笔（同一个 rate_group_id）：
  leg1:          SGD 1000，account=OK，  from_account=OK2
  leg2:          MYR 3000（flat 毛额）， account=OK2， from_account=OK
  Rate 分录:     MYR 100，  account=OK，  from_account=OK3   "MARKUP x2.9 SGD 1000 > MYR | FROM OK"
  Fee 分录:      MYR 8.5，  account=OK，  from_account=OK3   "MARKUP X SGD 1000 > MYR | FROM OK"
  Platform Fee:  MYR 1.5，  account=OK，  from_account=NULL  "CHARGE MYR 1.5 PLATFORM FEE"

transactions_rate 头表：
  middleman_rate = 2.9, middleman_rate_expression = "2.9"
  middleman_amount = 10, platform_fee_amount = 1.5
```

**OK（leg2 from account）看到的两种视图**：

| 视图 | 呈现方式 | 结果 |
|---|---|---|
| Payment History（第 10 节合并后） | RATE 一行 Cr/Dr（毛额 3000 − Rate-Mul 100 − Fee **满额** 10）+ Fee 一行 Cr/Dr（Platform Fee +1.5） | `2890.00` + `+1.50` = **2891.50** |
| CONTRA 汇总（第 11 节迁移后） | 一行 Cr/Dr（毛额 3000 − Rate-Mul 100 − Fee **净额** 8.5），Win/Loss = 0 | Cr/Dr **2891.50** |

两条路径算法不同（一个先还原满额再单独加 Platform Fee 一行，一个直接用净额），但代数上等价（`3000−100−10+1.5 = 3000−100−8.5 = 2891.5`），**总数必须始终一致**——以后改这块任何一边的公式，务必同时验算另一边，防止两个页面数字对不上。

middleman（OK3）看到的是未合并的原始视角：Rate `+100`、Fee `+8.5`，都走 Win/Loss，不受第 10、11 节改动影响。
