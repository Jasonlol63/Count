# Data Capture 明细行货币字段错配回填记录

> 背景：95 公司 NO 账号查看 Data Capture Payment History 时，`MAALLBET95SGD JDB` 这个 product 在旧版能看到，
> 新版整行"消失"。排查发现不是数据丢失，是这一行的货币被记错了，导致按账号自己的币种（MYR）查询时被过滤掉。
> 本文档记录问题根因、识别规则、用到的工具，以及最终执行结果。执行时间：2026-09-04。

---

## 1. 现象

95 公司 NO 账号，2026-03-09 这一批 Data Capture 提交里，旧版（`count168.com`）显示：

| DATE | ID PRODUCT | CURRENCY | RATE | WIN/LOSS |
|---|---|---|---|---|
| 09/03/2026 | MAALLBET95MYR JD SPINGO | MYR | - | 15.47 |
| 09/03/2026 | **MAALLBET95SGD JDB** | **MYR** | 3.114 | 11.33 |
| 09/03/2026 | MYCS33MYR | MYR | - | 48.19 |

新版（`count_real`）同一个账号、同一天，`MAALLBET95MYR JD SPINGO` 之后直接跳到 `MYCS33MYR`，
`MAALLBET95SGD JDB` 这一行完全不出现，前后 BALANCE 也因此对不上。

---

## 2. 根因

### 2.1 旧库 schema 允许"批次头"和"明细行"货币不一致

旧版 `data_captures`（批次头，一次 Submit 一条）跟 `data_capture_details`（明细行，一次 Submit 里的每个
account/product 各一条）**各自有独立的 `currency_id` 字段**，两者允许不同——一个批次头可能是按某个游戏商的
计价货币建的（这批是 SGD），但具体某个账号那一行实际是按**这个账号自己配置的货币**结算（这批是 MYR），
`rate`/`rate_expression` 字段（这行是 `3.114`）记录的就是换算这两种货币用的汇率，`formula` 里的原始数字仍是
SGD 那边的量。

核对过全库，这种"明细行货币 ≠ 批次头货币"的情况**不是个例**：

```sql
SELECT COUNT(*) FROM data_capture_details dcd
JOIN data_captures dc ON dc.id = dcd.capture_id
WHERE dcd.currency_id <> dc.currency_id;
-- 2946
```

### 2.2 迁移脚本取错了字段

[`migrate_data_datacapture_from_legacy.sql`](migrate_data_datacapture_from_legacy.sql) 第 123 行，
`data_capture_line.currency_id` 这一列取的是 `dc.currency_id`（已迁移的 `data_captures`**批次头**记录），
而不是 `dcd.currency_id`（`data_capture_details`**明细行自己**的货币字段）：

```sql
-- 原脚本（有问题）
dcd.formula_variant, dcd.display_order, CAST(dcd.account_id AS UNSIGNED), dc.currency_id,
--                                                                        ^^^^^^^^^^^^^^ 应该是 dcd.currency_id 映射后的值
```

`data_capture_formula` 那段 INSERT（脚本第 152 行）是对的——它本来就用自己那行的 `dct.currency_id` 做映射，
这次的 bug 只出在 `data_capture_line` 这一段 INSERT。

### 2.3 错误货币又被 §18 的补丁传染进了 `transactions`

[MIGRATION_LOG.md §18](MIGRATION_LOG.md) 后来给全库 75234 条从未生成 `transactions` 记录的 Data Capture
明细行补建了交易记录，字段映射明确写了"`account_id`/`currency_id`：明细行自己的，不是 header 的"——这个
判断本身没错，但当时读的 `data_capture_line.currency_id` 已经是被 2.2 这个 bug 写错的值了，所以补出来的
`transactions.currency_id` 跟着一起错。

### 2.4 为什么错误货币会导致整行"消失"而不是"显示成别的货币"

Payment History 按查看账号自己配置的货币过滤（`account_currency` 表，`TransactionHistoryMapper.findDataCaptureHistoryLines`
的 `currencyCodes` 条件）。95 公司 NO 账号只配置了 MYR 一种货币（`account_currency` 里只有一行），这一笔本该是
MYR 的记录被错误标成 SGD 后，查 MYR 视图时直接被 SQL 过滤条件排除——不会显示成"错误的 SGD 那一行"，
而是**完全不出现**，看起来就像这条记录凭空消失了。

---

## 3. 识别规则与验证

```sql
SELECT COUNT(*)
FROM c168_net_legacy_20260827.data_capture_details dcd
JOIN c168_net_legacy_20260827.data_captures dc ON dc.id = dcd.capture_id
WHERE dcd.currency_id <> dc.currency_id;
-- 2946，跟新库里 data_capture_line.currency_id 当前值（等于 header 的错误值）逐条比对，2946 条全部命中
```

抽查具体样例（txn id=20256，capture_id=5464，account=NO/3856）：

| 字段 | 旧库 `data_capture_details`（明细行，正确来源） | 旧库 `data_captures`（批次头） | 迁移前 `count_real` |
|---|---|---|---|
| currency_id | 169（MYR） | 170（SGD） | 170（SGD）——错，抄了批次头 |

回填前统计，2946 条覆盖多个 tenant、多种货币组合（不只是 MYR/SGD），最大的几组：

| 错误货币 → 正确货币 | 条数 |
|---|---|
| SGD → MYR | 1466 |
| SGD → MYR（另一 tenant，货币 id 不同） | 499 |
| SGD → MYR（第三个 tenant） | 212 |
| AUD → MYR | 143 + 121 |
| CNY → MYR | 137 |
| HKD → MYR | 110 + 18 |
| MYR → SGD | 52 |
| USD → MYR | 45 + 44 |
| HKD → MYR | 36 |
| MYR → USD | 20 + 13 |
| MYR → CNY | 15 |
| CNY → USD | 12 |
| PGK → MYR | 3 |

---

## 4. 修复

[`DataCaptureLineCurrencyFixTool.java`](DataCaptureLineCurrencyFixTool.java)：独立 JDBC 小工具。

1. 按 [`migrate_data_datacapture_from_legacy.sql`](migrate_data_datacapture_from_legacy.sql) 步骤 0 同一套
   去重规则（`manual` 优先于 `subsidiary`，同 company 内按 `id` 从小到大取存活行）重建 `_map_currency`；
2. 用重建的映射，把 `dcd.currency_id <> dc.currency_id` 这 2946 条对应的 `count_real.data_capture_line.currency_id`
   改成明细行自己的正确货币（映射后的存活 id）；
3. 把改动同步传导到通过 `data_capture_line.transaction_id` 关联的 `transactions.currency_id`（对**全部**
   Data Capture 关联交易做 `SET t.currency_id = dcl.currency_id WHERE dcl.currency_id <> t.currency_id`——
   本来就一致的行不会被这条语句误伤，只有这 2946 条真正被改）。

`data_capture_formula.currency_id` 没有被这次改动碰到——那段迁移逻辑本来就是对的，不需要修。

默认只预览、不写库，`--apply` 才真正执行：

```bash
./mvnw -q -o compile
./mvnw -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -cp "target/classes;$(cat cp.txt)" -d target/classes \
    backend/src/main/resources/SqlEtcForMigrate/DataCaptureLineCurrencyFixTool.java
java -cp "target/classes;$(cat cp.txt)" com.eazycount.service.impl.DataCaptureLineCurrencyFixTool \
    [--apply] [--report=xxx.txt]
```

### 执行结果

```
预览: legacy_mismatched=2946 line_rows_needing_fix=2946 line_rows_updated=0    txn_rows_updated=0    mode=PREVIEW
应用: legacy_mismatched=2946 line_rows_needing_fix=2946 line_rows_updated=2946 txn_rows_updated=2946 mode=APPLY
```

全库核对：

```sql
SELECT COUNT(*) FROM transactions t
JOIN data_capture_line dcl ON dcl.transaction_id = t.id
WHERE t.currency_id <> dcl.currency_id;
-- 0（回填前是 2946）
```

抽查 txn id=20256：`currency_id` 从 170（SGD）变成 169（MYR），跟旧版截图 CURRENCY 列显示的 "MYR" 一致；
再次按 MYR 币种查询 95 公司 NO 账号的 Payment History，这一行会正常出现。

---

## 5. 遗留风险 / 以后重跑迁移时的提醒

- 这次修复只处理了 `data_capture_line`/`transactions` 两张表。如果以后重新执行
  `migrate_data_datacapture_from_legacy.sql` 迁移一份新的旧库备份，第 123 行这个字段来源本身还是错的
  （脚本没有同步改），需要同时改这个脚本本身（把 `dc.currency_id` 换成按 `dcd.currency_id` 重新走
  `_map_currency` 映射），否则同样的 bug 会在下一次全量重跑时原样复现。**这次没有直接改迁移脚本**，
  是因为发现问题时数据已经迁移完、其他域已经在这份数据上继续开发；如果以后要重跑全量迁移，务必先改好
  脚本再跑，不要指望这个一次性修复工具能覆盖"重新迁移"的场景。
- 识别规则依赖"`data_capture_details`/`data_capture_line` 的 id 在迁移时 1:1 保留"这个不变量（§5 已确认），
  这次核对时也重新验证过全库 77859 条（含迁移后的新提交）行 id 能完全对应，没有孤儿。
