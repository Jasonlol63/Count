# 手动 Profit 转账 WIN/LOSE 误分类回填记录

> 背景：95 公司 EXPENSE 账号的 Payment History 里，"PROFIT FROM KZ"/"PROFIT FROM RS" 这类手动利润分成记录，
> 新版页面 ID PRODUCT 显示成 `DATA CAPTURE`、description 显示为空、Win/Loss 金额正负号也跟旧版不一致。
> 本文档记录问题根因、识别规则、用到的工具，以及最终执行结果。执行时间：2026-09-04。

---

## 1. 现象

旧版（`count168.com`）Payment History：

| DATE | ID PRODUCT | WIN/LOSS | DESCRIPTION | REMARK |
|---|---|---|---|---|
| 27/04/2026 | `PROFIT` | -4,424.00 | PROFIT FROM KZ | 2026/04 - 30% |
| 27/04/2026 | `PROFIT` | -2,212.00 | PROFIT FROM RS | 2026/04 - 15% |

新版（`count_real` / Spring Boot）同一批记录：

| DATE | ID PRODUCT | WIN/LOSS | DESCRIPTION | REMARK |
|---|---|---|---|---|
| 27/04/2026 | `DATA CAPTURE` | +4,424.00 | - | 2026/04 - 30% |
| 27/04/2026 | `DATA CAPTURE` | +2,212.00 | - | 2026/04 - 15% |

不只是 ID PRODUCT/description 显示错——Win/Loss **金额符号也相反**，说明不是单纯的展示 bug，是这批记录在
新系统里被路由进了错误的业务分支。

---

## 2. 根因

### 2.1 新系统靠 `transaction_type` 精确匹配路由，不是靠字段组合推断

新系统对 Payment History 的 Win/Loss 数据源做了明确切分（[`TransactionHistoryMapper.xml`](../../mybatis/TransactionHistoryMapper.xml)）：

- `transaction_type IN ('WIN','LOSE')` + `bank_process_posted_id IS NULL` → 当成 **Data Capture** 行，
  `LEFT JOIN data_capture_line` 取 `idProduct`；查不到时兜底显示 `"DATA CAPTURE"`
  （[`TransactionHistoryServiceImpl.java:333`](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java)）。
  **这是符合设计的**——"Data Capture Summary Submit"功能本身产出的 WIN/LOSE 行就应该显示 `DATA CAPTURE`
  （见 `docs/frontend-springboot-migration.md` 第 5515-5517 行），不是这次要修的问题。
- `transaction_type = 'PROFIT'` → 当成**手动 Profit 转账**行，ID PRODUCT 固定显示 `PROFIT`
  （[`TransactionHistoryServiceImpl.java:325-326`](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java)），
  description 为空时按 `PROFIT FROM {收款方}` / `PROFIT TO {付款方}` 现算
  （`applyManualTransferHistoryPresentation`/`shouldRewriteManualTransferHistoryDescription`），
  Win/Loss 金额符号按"当前查看的账号是 `account_id` 还是 `from_account_id`"决定，
  跟 Data Capture 分支"WIN 恒正、LOSE 恒负"是两套完全不同的规则。

`PROFIT` 是新系统专门为"手动 PROFIT Submit"功能新增的枚举值（`from_account_id` + `account_id`、单行、正数
amount、`description` 现算，见 [`TransactionSubmitServiceImpl.submitProfit`](../../java/com/eazycount/service/impl/TransactionSubmitServiceImpl.java)），
`docs/frontend-springboot-migration.md` 第 1348 行记录了这次加枚举值的脚本
（`migrate_transaction_type_add_profit.sql`），上线时间 2026-07-23。

### 2.2 旧库这批记录本身就没有 `PROFIT` 这个类型

旧版 PHP 系统（`count168.site`）没有对应的枚举值——同样是"从 A 账号手动转一笔到 B 账号，单行记账、
attach 一句自由文本备注"这个功能，旧系统底层用的是 `transaction_type='WIN'`（这批数据里从未出现过 `LOSE`），
靠 `from_account_id` 是否有值在渲染时跟"真正的 Data Capture Win/Loss"区分开，旧版前端把这类行统一显示成
`ID PRODUCT = PROFIT`、`description = "PROFIT FROM {对手方}"`（不看 remark 内容）。

[`migrate_data_transactions_from_legacy.sql`](migrate_data_transactions_from_legacy.sql) 对 `transaction_type`
是逐行原样搬（`t.transaction_type` verbatim，无 WHERE 按类型过滤，见该脚本第 78-107 行），没有对"WIN 类型但其实是
手动 Profit 转账"这批记录做重新分类，于是它们在新库里还是 `WIN`，被新系统的路由规则误判成 Data Capture 行。

**结论**：这不是这次迁移脚本"写错"了，而是**遗漏的一类重分类**——跟 MIGRATION_LOG.md §5.2（21 条 process
应该是 BANK 不是 GAME）、§8（TRUSTY HAULERS/SUPPER SERVICE 的 `FULL_MONTH` 误分类）是同一种性质的问题：
旧库字段值本身在旧系统语境下是"对的"，只是新旧两套 schema 对同一类业务事实的编码方式不同，迁移时字段值
1:1 保留导致新系统读出了错误的含义。

---

## 3. 识别规则与验证

回填前用生产库核对过，确认这批记录的共同特征：

```sql
transaction_type IN ('WIN','LOSE')
AND from_account_id IS NOT NULL        -- 真正的 Data Capture / Bank Process WIN/LOSE 行从不写这个字段
AND bank_process_posted_id IS NULL     -- 核对过 0 条 Bank Process 行会有 from_account_id
AND NOT EXISTS (
    SELECT 1 FROM data_capture_line dcl WHERE dcl.transaction_id = t.id
)                                        -- 核对过 0/80 条能关联到真实的 Data Capture 明细行
```

全库排查命中 **80 条**，全部是 `WIN`（没有 `LOSE`）、`description` 全部为空字符串、`data_capture_line`
关联数全部为 0，分布在 5 个 tenant：

| Tenant | 条数 |
|---|---|
| AG | 54 |
| RS | 10 |
| 95 | 10 |
| 23 | 3 |
| TZX | 3 |

抽查 remark 内容发现这批记录不只是"利润分成"（`2026/04 - 30%` 这类百分比备注），也有水电房租
（`WATER AUG'26`、`RENTAL BAYU SEPT'26`）、银行账户操作（`BANK ACC`、`PHONE TOPUP`）、佣金（`COMM`）等各种
自由文本——**这不影响判断**：新系统"手动 PROFIT Submit"功能的 ID PRODUCT 本来就固定显示 `PROFIT`，不看
remark 内容决定标签，旧系统同理（旧版这个功能本身就是"通用的手动两账户转账"，只是统一挂在 `PROFIT` 标签
下，用户拿它记过各种性质的手动调整，不只是狭义的"利润分成"）。

---

## 4. 修复

[`ManualProfitTypeReclassifyTool.java`](ManualProfitTypeReclassifyTool.java)：独立 JDBC 小工具（不依赖 Spring
容器），按第 3 节的规则查出记录，把 `transaction_type` 改成 `PROFIT`。

**只改了 `transaction_type` 一个字段，没有碰 `description`**——因为这批记录的 `description` 本来就是空的，
而 `TransactionHistoryServiceImpl.applyManualTransferHistoryPresentation` /
`shouldRewriteManualTransferHistoryDescription` 对**任何** `description` 为空的 `PROFIT` 行都会在读时现算
`"PROFIT FROM {code}"` / `"PROFIT TO {code}"`（跟一笔全新提交的 PROFIT 交易的展示逻辑完全一样），不需要
这个工具额外写一份存库文案。

默认只预览、不写库，`--apply` 才真正执行：

```bash
./mvnw -q -o compile
./mvnw -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -cp "target/classes;$(cat cp.txt)" -d target/classes \
    backend/src/main/resources/SqlEtcForMigrate/ManualProfitTypeReclassifyTool.java
java -cp "target/classes;$(cat cp.txt)" com.eazycount.service.impl.ManualProfitTypeReclassifyTool \
    [--apply] [--report=xxx.txt]
```

### 执行结果

```
预览: total=80 reclassified=80 skipped_non_blank_description=0 mode=PREVIEW
应用: total=80 reclassified=80 skipped_non_blank_description=0 mode=APPLY
```

全库核对：

```sql
SELECT
  (SELECT COUNT(*) FROM transactions WHERE transaction_type='PROFIT') AS profit_count,        -- 80
  (SELECT COUNT(*) FROM transactions WHERE transaction_type IN ('WIN','LOSE')
                                        AND from_account_id IS NOT NULL) AS remaining_misclassified -- 0
```

回填后，这 80 条在 Payment History 里会正确显示为 `ID PRODUCT = PROFIT`，description 现算成
`PROFIT FROM {code}`/`PROFIT TO {code}`，Win/Loss 金额符号也会跟旧版一致（不再是 Data Capture 分支的
`WIN 恒正` 规则，而是按查看方是收款方还是付款方决定正负）。

---

## 5. 遗留风险 / 以后重跑迁移时的提醒

- 这次核对只覆盖了"当前这份 2026-08-27 备份迁移出来的数据"。如果以后换一份新的旧库备份重新跑
  `migrate_data_transactions_from_legacy.sql`，同样的"WIN/LOSE + from_account_id 手动转账"记录还会
  原样进来，需要重新跑一遍第 3 节的识别 SQL 确认条数、再跑本工具（或者更彻底的做法是把这条重分类规则
  直接并进 `migrate_data_transactions_from_legacy.sql` 本身，作为迁移脚本的一部分，而不是事后补丁——
  这次没有这样做，是因为发现问题时数据已经迁移完、其他域已经在这份数据上继续开发，改迁移脚本本身
  不会影响已经存在的库，需要额外决定要不要回头改脚本）。
- 识别规则依赖"真 Data Capture 行必有对应的 `data_capture_line`"这个不变量——如果以后 Data Capture 那边
  出现"允许没有明细行的 WIN/LOSE 头"这种新场景，这条规则需要重新核对，不能假设永远成立。
