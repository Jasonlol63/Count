# Bank Process 历史交易 Description / ID Product 回填记录

> 背景：CX 等公司 Bank Process 交易的 Payment History 里，绝大多数历史记录 description 显示的是旧版
> PHP 系统当年写入的原始文案（`Process: Profit for X ...`），而不是新版 `BankAccountingDueServiceImpl`
> 现在会生成的格式（`MONTHLY BILL`/`FULL MONTH`/`PRORATED`...）；另外有少量记录 ID PRODUCT 显示成
> `DATA CAPTURE`，而不是自己的 process 名字。
> 本文档记录问题根因、用到的工具、代码改动，以及最终执行结果。执行时间：2026-09-03。

---

## 1. 问题根因

### 1.1 Description 大面积显示旧格式

新版 [`TransactionHistoryServiceImpl.toHistoryRow`](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java) 对 Bank Process 行只是把
`transactions.description` 原样回显（`row.setDescription(trimToEmpty(line.getDescription()))`），从不重新计算。
description 只在**生成过账记录的那一刻**由 [`BankAccountingDueServiceImpl.buildPostDescription`](../../java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java) 算一次并写死进库。

旧版 PHP 系统（`count168.site`）恰恰相反：Payment History 页面从不信任存库的 description，而是查询时用
`bank_process.day_start`/`day_end`/`cost`/`price`/`profit` 等结构化字段现算（见旧仓库
`api/transactions/bank_process_bill_display.php`），所以哪怕是很老的记录也总能显示"正确"格式。

结果：`count_real` 里几乎所有 Bank Process 交易都是从旧库迁移过来的历史数据，新系统上线后真正走过一次
posting 流程的记录极少 —— 迁移时 description 原样保留了旧文案，新系统又没有"读时重算"的兜底，于是旧文案一直穿透显示到前端。

### 1.2 ID PRODUCT 显示 DATA CAPTURE

[`TransactionHistoryMapper.xml`](../../mybatis/TransactionHistoryMapper.xml) 按 `t.bank_process_posted_id`
是否为空把 Win/Loss 行分流进两条 SQL：不为空走 `findBankProcessHistoryLines`（`INNER JOIN bank_process_accounting_posted`/`bank_process`
取 `card_owner` 作为 ID PRODUCT）；为空则被当成 Data Capture 行处理，`card_owner` 恒为 NULL，最终命中
[`TransactionHistoryServiceImpl`](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java) 里的兜底值 `"DATA CAPTURE"`。

根因是 §13 迁移脚本（`migrate_data_bank_process_accounting_due_from_legacy.sql`）只成功回填了
509 条里的 494 条 `bank_process_posted_id`，剩下 15 条对应的旧账本记录本身就是孤儿数据，回填不了
（见 [MIGRATION_LOG.md §13.2/§13.4](MIGRATION_LOG.md)），这 15 条因此被误判成 Data Capture 行。

### 1.3 追加发现：74 条已关联记录的台账日期是空的

排查过程中还发现：§13 迁移脚本从头到尾没有给 `bank_process_accounting_posted.billing_start`/`billing_end`
赋值（全库 247 条台账里 238 条是 NULL）。这两个字段在 `PARTIAL_FIRST_MONTH`/`DAY_END_TAIL`/`RESEND_CONSOLIDATED`
这三种 period_type 计算 `PRORATED(...)` 文案时是必需的，缺了就会在重算 description 时抛 NPE，因此这部分记录
（74 条，涉及 19 个 bank_process）被第一轮回填跳过，需要单独处理。

---

## 2. 关键代码不变量（用来做无损回填的依据）

- `billing_start` **恒等于** `posted_date`（验证于三处生成逻辑：
  [`buildFirstOfMonthDueForMonth`](../../java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java)、
  Weekly 生成、`BankProcessResendServiceImpl#resolveWindow`）。
- `PARTIAL_FIRST_MONTH`/`DAY_END_TAIL` 的 `billing_end` 能从 `bank_process.day_start`/`day_end` 按现有代码规则精确算出。
- `RESEND_CONSOLIDATED` 的 `billing_end`：旧版 PHP 写入时会在 description 里留一个
  `[RESEND_END=yyyy-mm-dd]` 标记（见旧仓库 `api/processes/process_post_to_transaction_api.php:1827`），
  绝大多数记录这个标记还原样躺在库里，可以直接精确解析，不需要猜。
- 极少数没有标记的（3 个 TRAVELMINI 早期事件），用"链式推算"：同一个 bank_process 按时间排序，
  `billing_end` = 下一个已知周期的 `billing_start` 往前推一天。

---

## 3. 用到的工具

三个一次性 Java 命令行小工具（前两个是这次回填主体，第三个是第 8 节的后续修复），都放在
`com.eazycount.service.impl` 包下（这样才能直接复用
`BankAccountingDueServiceImpl` 里生成 description 用的静态方法，保证回填出来的文案和新记录一模一样的格式）。
都是纯 JDBC 实现，不依赖 Spring 容器，默认**只预览、不写库**，加 `--apply` 才真正执行。

### 3.1 [`BankProcessDescriptionBackfillTool.java`](BankProcessDescriptionBackfillTool.java)

第一轮：对所有 `bank_process_posted_id IS NOT NULL`（已正确关联）的交易，按 leg（supplier/customer/company/share）
取对应的 `bank_process` 原始价格作为 `baseAmount`，调用
`BankAccountingDueServiceImpl.buildLineDescription(...)` 重新生成 description 并回写。

```bash
java -cp <classpath> com.eazycount.service.impl.BankProcessDescriptionBackfillTool \
    [--tenant=82] [--apply] [--report=xxx.txt]
```

### 3.2 [`BankProcessLedgerBackfillTool.java`](BankProcessLedgerBackfillTool.java)

第二轮，分三个阶段：

1. **Phase 1**：给 `bank_process_accounting_posted` 里缺 `billing_start`/`billing_end` 的行补日期（按第 2 节的规则），
   排除 `bank_process_id = 469`（那 3 条 description 已经是新格式、只是台账日期缺失，是另一个独立的实时系统 bug，按要求本轮不动）。
2. **Phase 2**：把 15 条孤儿交易按人工核对过的 `txnId -> bankProcessId` 映射（tenant + account_id + 金额 + 公司名交叉核对，见工具源码里的
   `ORPHAN_BANK_PROCESS` 静态表）分组成 5 个入账事件，插入缺失的 `bank_process_accounting_posted` 台账行，
   再把对应 transactions 的 `bank_process_posted_id` 指过去。
3. **Phase 3**：对 Phase 1/2 涉及到的所有台账行，复用 `BankProcessDescriptionBackfillTool` 同一套逻辑重新生成 description。

```bash
java -cp <classpath> com.eazycount.service.impl.BankProcessLedgerBackfillTool [--apply] [--report=xxx.txt]
```

### 3.3 [`BankProcessDayEndTailFixTool.java`](BankProcessDayEndTailFixTool.java)

第三个工具，范围很窄、针对性很强，详见 [第 8 节](#8-后续修复trusty-haulers--supper-service-的-full_month-误分类2026-09-03用户复查发现)。

### 3.4 运行方式

```bash
./mvnw -q -o compile                                            # 编译
./mvnw -q dependency:build-classpath -Dmdep.outputFile=cp.txt    # 生成 classpath（需要联网，插件不在离线缓存里）
java -cp "target/classes;$(cat cp.txt)" com.eazycount.service.impl.<ToolName> [参数]
```

两个工具都用同一个模式：**预览时也会在同一个数据库事务里真正执行 UPDATE/INSERT**（这样 Phase 3 才能看到
Phase 1/2 写入后的效果），只是最后按 `--apply` 决定 `COMMIT` 还是 `ROLLBACK`，所以不加 `--apply` 时数据库不会有任何实际改动。

---

## 4. 代码改动

只有一处生产代码改动，且是**纯可见性放宽，没有改任何逻辑**：

[`BankAccountingDueServiceImpl.java`](../../java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java)
把 `buildLineDescription` 和 `isCompensationPost` 从 `private static` 改成包内可见的 `static`，
以便两个回填工具类（同包）可以直接复用，保证回填文案和新记录用的是**完全相同**的一套格式规则。

---

## 5. 执行结果

### 5.1 Description 回填（`BankProcessDescriptionBackfillTool`）

先在 CX（tenant=82）预览+核对无误后 apply，再对全库 apply：

| 范围 | total | changed | unchanged | skipped |
|---|---|---|---|---|
| CX（tenant=82） | 474 | 420 | 3 | 51 |
| 全库 | 497 | 0（CX 已处理，其余 tenant 本身就落在 skip 里） | 423 | 74 |

skipped 的 74 条全部是"billing_start/billing_end 缺失"（1.3 节的问题），转交第二个工具处理。

### 5.2 台账补全 + 孤儿链接 + 二次回填（`BankProcessLedgerBackfillTool`）

```
phase1(补台账日期): fixed=23  unresolved=0  excluded(bank_process_id=469)=1
phase2(孤儿记录建档+链接): events_created=5  txns_linked=15
phase3(重算description): changed=89  unchanged=0  skipped=0
```

（89 = 74 条原本卡住的 + 15 条孤儿）

### 5.3 全库最终核对

```sql
SELECT
  SUM(bank_process_posted_id IS NOT NULL AND description LIKE 'Process: %') AS still_old_format,  -- 0
  SUM(bank_process_posted_id IS NOT NULL) AS linked_total,                                        -- 512（497 + 新链接 15 条）
  SUM(bank_process_posted_id IS NULL AND description LIKE 'Process: %') AS remaining_orphans       -- 0
FROM transactions;
```

抽查样例（回填前 → 回填后）：

| 记录 | 回填前 | 回填后 |
|---|---|---|
| TRAVELMINI 01/06 | `Process: Profit for TRAVELMINI SDN BHD \| RHB` | `FULL MONTH (JUN 2026) @MONTHLY 200 \| RHB` |
| LIANG FISHING 17/08 | `Process: Profit for LIANG FISHING SDN BHD` | `MONTHLY BILL 200 \| CIMB` |
| TRAVELMINI 孤儿事件1（18/03） | `Process: Profit for TRAVELMINI SDN BHD (resend consolidated)` | `PRORATED(18/3 - 31/3 \| 14 DAYS)@MONTHLY 200 \| RHB` |
| TRAVELMINI 孤儿事件2（01/04） | 同上 | `PRORATED(1/4 - 30/4 \| 30 DAYS)@MONTHLY 200 \| RHB`（和事件1无缝衔接） |
| SUPPER SERVICE 孤儿事件 | `Process: Profit for SUPPER SERVICE PTE.LTD (resend consolidated) [RESEND_END=2026-05-31]` | `PRORATED(1/5 - 31/5 \| 31 DAYS)@MONTHLY 150 \| ANEXT` |
| CARGO SOLUTIONS 孤儿事件 | `Process: Profit for CARGO SOLUTIONS PTE LTD` | `MONTHLY BILL 500 \| OCBC` |

---

## 6. 过程中发现并修的一个工具 bug

`BankProcessLedgerBackfillTool` Phase 2 第一版的"链式推算"只查了数据库里已有的台账记录，没考虑
**同一批正在处理的其他孤儿事件**，导致 TRAVELMINI 的两个连续孤儿事件（18/03、01/04）都被推算成
同一个结束日期（错误地跳过了中间那个事件）。修复方式：额外维护一份"本轮所有孤儿事件的 billing_start
集合"，链式推算时取"数据库里下一条 + 本轮兄弟事件"两者中较早的一个。修完后重新预览验证两个事件正确
首尾相接（18/3-31/3、1/4-30/4）才实际写库。

---

## 8. 后续修复：TRUSTY HAULERS / SUPPER SERVICE 的 `FULL_MONTH` 误分类（2026-09-03，用户复查发现）

### 8.1 现象

用户逐条核对 CX 全公司数据后发现：`TRUSTY HAULERS PTE LTD`（`bank_process=457`）、
`SUPPER SERVICE PTE LTD`（`bank_process=458`）01/08/2026 那笔记账，旧版显示
`PRORATED(1/8 - 12/8 | 12 DAYS)@MONTHLY 800 | CIMB`，新版却显示 `FULL MONTH (AUG 2026) @MONTHLY 800 | CIMB`，
但 WIN/LOSS 金额都是 `309.68`（= 800 × 12/31，明显是按 12 天算的），文案和金额对不上。

### 8.2 根因

这两个 `bank_process` 都设置了 `day_end_monthly_cap_enabled=1`，且 `day_end`（2026-08-11，本地日历 08/12）落在
8 月月中——按现有代码 [`buildFirstOfMonthDueForMonth`](../../java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java) 的规则，
这种情况应该生成 `period_type=DAY_END_TAIL`，但 `bank_process_accounting_posted`（`id=1696`/`1697`）里存的却是 `FULL_MONTH`。

核实过**不是这次两个回填工具造成的**——两个工具从未写过 `period_type` 这一列；回填前的原始旧文案
（`Process: Profit for TRUSTY HAULERS PTE LTD`，没有 `(day end tail)` 后缀）说明这个分类错误在我们介入之前就已经存在，
应该是当年旧版 PHP 系统写入 / 迁移脚本转换时就分类错了（金额那边的比例算对了，只是记录的 period_type 类型错了）。
旧版页面之所以显示正确，是因为它的 History 是**查询时现算**的（`bankProcessMonthlyDayEndCapHistoryDescription`，
不管存库分类是什么，都会用当前 `day_end_monthly_cap_enabled`/`day_end` 重新判断），新系统没有这层兜底，只会照着存库的
`period_type` 拼文案，于是错误就直接透出来了。

排查过全库，符合"该判 DAY_END_TAIL 却存成 FULL_MONTH/FIRST_MONTH"这个模式的一共 5 条台账记录，
其余 3 条（tenant 94/96）名下都没有挂交易、不影响任何人看到的数据，只有这 2 条（共 6 笔交易）需要处理。

### 8.3 工具：[`BankProcessDayEndTailFixTool.java`](BankProcessDayEndTailFixTool.java)

范围写死成人工核实过的两个台账 id（`TARGET_LEDGER_IDS = {1696, 1697}`），不是通用的"全库扫描修复"工具——
刻意避免碰到那 3 条没有交易的空台账，也避免用一个没有单独复核过的宽泛查询条件去动数据。

对每个目标台账行：`period_type` 改成 `DAY_END_TAIL`，`billing_start = posted_date`，`billing_end = bank_process.day_end`
（复用现有代码同一条规则），再对挂在它下面的交易重新跑一遍 description 生成逻辑
（`BankAccountingDueServiceImpl.buildLineDescription`，跟前面两个工具同一套复用方式）。同样默认只预览，`--apply` 才写库。

```bash
java -cp <classpath> com.eazycount.service.impl.BankProcessDayEndTailFixTool [--apply] [--report=xxx.txt]
```

### 8.4 执行结果

```
mode=APPLY  ledger_fixed=2  description_changed=6
```

| 记录 | 修复前 | 修复后 |
|---|---|---|
| TRUSTY HAULERS 01/08（供应商/客户/公司三条） | `FULL MONTH (AUG 2026) @MONTHLY 3500/4300/800 \| CIMB` | `PRORATED(1/8 - 12/8 \| 12 DAYS)@MONTHLY 3500/4300/800 \| CIMB` |
| SUPPER SERVICE 01/08（供应商/客户/公司三条） | 同上 | 同上（金额分别是各自的 3500/4300/800） |

金额本身没有任何改动，只修正了 description 文案和它背后的 `period_type`/台账日期。

---

## 9. 已知问题：BIKE RESCUE 的 description 回归（暂不处理）

用户复查时还发现 `BIKE RESCUE PTE LTD`（`bank_process=469`）17/05/2026 那笔记账，旧版显示
`PRORATED(17/5 - 16/6 | 31 DAYS)@MONTHLY 500 | OCBC`，新版却是 `MONTHLY BILL 500 | OCBC`，丢失了补单覆盖的日期区间信息。

**排查后确认：这不是迁移遗留问题，而是本次回填工具（`BankProcessDescriptionBackfillTool`）自己触发的一次回归**，
根子在 [`buildPostDescription`](../../java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java) 本身的一个代码空档：

```java
if (frequency == BankProcess.Frequency.MONTHLY) {
    return "MONTHLY BILL " + amt + " | " + bank;   // 完全不看 periodType，哪怕是 RESEND_CONSOLIDATED 也一样
}
...
if (frequency == BankProcess.Frequency.FIRST_OF_EVERY_MONTH) {
    // 只有这个频率才会检查 periodType 是不是 PARTIAL_FIRST_MONTH/DAY_END_TAIL/RESEND_CONSOLIDATED，
    // 拼出带日期区间的 PRORATED(...) 文案
}
```

这条记录的 `bank_process.frequency = MONTHLY`，但这次记账是走 Resend 补单生成的（`period_type=RESEND_CONSOLIDATED`）。
回填前的原始文案其实是对的、信息更全：`Process: Profit for BIKE RESCUE PTE LTD (resend consolidated) [RESEND_END=2026-06-16]`
（`[RESEND_END=...]` 标记精确记录了补单覆盖到 16/06）。因为 `buildPostDescription` 对 `MONTHLY`/`ONCE`/`WEEK`/`DAY`
频率完全不检查 periodType，回填工具调用它时就把这段更完整的旧文案，覆盖成了信息更少的 `MONTHLY BILL 500 | OCBC`。

不影响金额——MONTHLY 频率的 Resend 补单本身不按天数打折（`ratio` 固定为 1，全额收），500/2500/3000 这些数字一直是对的，
纯粹是文案丢了"这是补单、覆盖了哪段日期"的说明。

全库排查过，符合"`period_type=RESEND_CONSOLIDATED` 但 `frequency` 不是 `FIRST_OF_EVERY_MONTH`"这个模式的**只有
这 3 条**（都是 BIKE RESCUE 这一个 process），范围很小。

**按用户要求本轮不修**，需要同时改两处才能修完整：
1. **代码**：给 `buildPostDescription` 的 `MONTHLY`/`ONCE`/`WEEK`/`DAY` 分支也补上"periodType 是 RESEND_CONSOLIDATED
   时拼 `PRORATED(...)`"的判断，跟 `FIRST_OF_EVERY_MONTH` 那段对齐，避免以后任何频率的 process 走 Resend 补单再丢文案。
2. **数据**：这 3 条现在的 `billing_start`/`billing_end` 是 NULL，需要从执行报告里找回原始的 `[RESEND_END=2026-06-16]`
   标记倒推回去补上，再重新生成一次 description。

---

## 10. 明确没做、留到以后处理的部分

- **BIKE RESCUE（`bank_process=469`）的 description 回归**：见第 9 节，涉及生产代码逻辑（`buildPostDescription`），
  按要求本轮不修，需要用户确认后再动。
- 本次改动（含第 8 节的修复）只涉及数据回填 + 2 处可见性放宽，**没有改变任何业务逻辑**，也没有改动 History 页面
  "读时不重算"的架构——如果以后同类迁移遗留数据再出现类似问题，需要重新跑一次这几个工具，或者考虑把 History 页面
  也改成像旧版 PHP 那样"读时按结构化字段现算"，从根上避免"description 写死后再也不会更新"的问题
  （这个改动面更大，未在本次范围内）。
