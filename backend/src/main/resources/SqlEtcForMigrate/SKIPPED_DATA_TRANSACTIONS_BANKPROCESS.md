# Transactions / Bank Process 域：没迁进 `count_real` 的数据清单

> 只覆盖这两个域（对应脚本 [`migrate_data_transactions_from_legacy.sql`](migrate_data_transactions_from_legacy.sql)、
> [`migrate_data_bank_process_from_legacy.sql`](migrate_data_bank_process_from_legacy.sql)、
> [`migrate_data_bank_process_accounting_due_from_legacy.sql`](migrate_data_bank_process_accounting_due_from_legacy.sql)）。
> 完整的迁移决策记录见 [`MIGRATION_LOG.md`](MIGRATION_LOG.md) §12/§13，这份文档只做"哪些数据没进新库、为什么"的汇总速查，细节以 MIGRATION_LOG.md 为准。
> 其他域（Data Capture description 桥表、user 精细 ACL 等）的已知缺口不在这份清单里，见 MIGRATION_LOG.md §2/§5/§8。

---

## 总览

| 域 | 表 | 旧库总数 | 已迁 | 跳过 | 跳过原因 |
|---|---|---|---|---|---|
| Transactions | `transactions` | 11685 | 11685 | 0 | — |
| Transactions | `transactions_rate` | 175 组 | 173 | 2 组 | 旧数据本身缺第二条腿 |
| Transactions | `transactions_deleted` | 2907 | 1048 | 1859 | 1810 条公司/账号早已不存在 + 49 条枚举值不合法 |
| Bank Process | `bank_process` | 185 | 185 | 0 | — |
| Bank Process | `bank_process_share`（Profit Sharing 解析） | 40 行文本，展开 56 行 | 56 | 0 | — |
| Bank Process | `bank_process_resend_daily_guard` | 89 | 81 | 8 | 公司早已不存在 |
| Bank Process | `bank_process.resend_schedule_*`（开放中补单） | 1（唯一真实开放的） | 1 | 0 | 已确认全库仅此一条 |
| Bank Process | `bank_process_maintenance_resend_pending` | 298 | 0 | 298 | 整张表不迁——核实后是审计/清理索引，不是业务数据，新 schema 没有对应表 |
| Bank Process | `bank_process_accounting_posted`（来自 `process_accounting_posted`） | 921 | 245 | 676 | 675 条公司/process 早已不存在 + 1 条内部重复（同一日期 POSTED/SKIPPED 各一条，SKIPPED 那条被去重掉） |
| Bank Process | `bank_process_accounting_posted`（来自 `process_accounting_due_dismissed`） | 25 | 10 | 15 | 5 条公司/process 早已不存在 + 10 条跟 `process_accounting_posted` 重复（信息没丢，另一边已经有等价记录） |
| Bank Process | `transactions.bank_process_posted_id` 回填 | 509 条待回填 | 494 | 15 | 对应的 posted 记录本身就是孤儿，回填不了 |

---

## 逐项详情

### 1. `transactions_rate`：2 组没建 header

`RATE_1779623477_1884`、`RATE_1786120634_9253`——旧库这两组本身就只记录了一条 `transactions` 行（该有的"腿 2"从来没被写进去过，其中一组两边币别还不一样，不是同币别走个形式），新表 `leg2_transaction_id` 是 NOT NULL，没法编一个出来。这条腿本身对应的那一行 `transactions` 数据是正常迁移了的，只是没有 `rate_group_id`、也没有配对的 RATE 搭档。

### 2. `transactions_deleted`：1859 条跳过

- **1810 条**：`company_id` 或 `account_id` 在旧库里已经彻底查不到——核实过 `deleted_at` 都停在 2026-03，而旧库"公司删除归档"功能最早的记录是 2026-06 才开始有，说明这批是归档机制上线之前发生的公司删除，当时没有任何机制保留这些公司的身份信息，现在没法倒推它们原本属于哪个 tenant。不可恢复。
- **49 条**：`transaction_type` 不是新枚举的合法值（48 条是空字符串、1 条是 `RECEIVE`，新枚举里已经没有这个值了）。

### 3. `bank_process_resend_daily_guard`：8 条跳过

同上，引用的 `company_id` 在旧库里已经不存在，同一批"归档功能上线前的历史孤儿"。

### 4. `bank_process_maintenance_resend_pending`：整张表（298 行）没迁

去读了旧版 PHP 代码（`maintenance_accounting_resend_lib.php`）才确认：这张表是"这笔已过账记录是不是从某次 Resend 批次来的"审计/清理索引，专门给 Maintenance 删除功能用的，不是"当前有没有开着的补单排程"。新 schema 里 `bank_process_accounting_posted.period_type=RESEND_CONSOLIDATED` 这个标记本身已经足够表达"这是一次 Resend 合并入账"，不需要额外的关联表去记录"这笔账当初是哪次 Resend 产生的"。真正代表"当前开放中补单排程"的信号在 `bank_process` 自己身上（`accounting_resend_relax_created_floor` + `accounting_resend_schedule_*`），已经在这批数据里找到唯一一条真实开放的记录并回填（见总览表倒数第 4 行）。

### 5. `bank_process_accounting_posted`（来自 `process_accounting_posted`）：676 条跳过

- **675 条**：`company_id` 或 `process_id`（= `bank_process.id`）在旧库里已经不存在，同样是"归档功能上线前"的历史孤儿。**这里面包含全部 60 条 `manual_inactive`（1+N 合同违约金）记录**——虽然已经确认 `manual_inactive` 该映射到新枚举的 `COMPENSATION`，但这 60 条本身全部是孤儿，一条都没能实际迁进去。
- **1 条**：`bank_process.id=420` 在同一天（2026-05-31 前后一天，时区显示问题，实际对应旧库 `posted_date=2026-05-31`）同时有一条 `day_end_tail`（已过账，2026-06-01 入账）和一条 `day_end_tail_skipped`（更晚，2026-07-24 才记录）——新表唯一键放不下两条，判定"已过账"那条应该保留（后面还有真实的 `transactions` 挂着），更晚那条 SKIPPED 记录被当作重复处理痕迹跳过。

### 6. `bank_process_accounting_posted`（来自 `process_accounting_due_dismissed`）：15 条跳过

- **5 条**：同样的公司/process 孤儿问题。
- **10 条**：跟 `process_accounting_posted` 自己的 `*_skipped` 行指向同一个 `(bank_process, 日期, 类型)`——不是数据丢失，是这条信息已经从另一张表迁进去了，为了不撞新表的唯一键才没有再插一次。

### 7. `transactions.bank_process_posted_id` 回填：15 条没能回填

509 条带 `source_bank_process_id` 的旧 `transactions` 里，494 条成功关联到迁移后的 `bank_process_accounting_posted` 记录；剩下 15 条对应的旧 `process_accounting_posted` 记录本身就落在上面第 5 项"675 条孤儿"里，没有对应的新记录可以关联，这些 `transactions` 行本身金额都是正常迁移的，只是 `bank_process_posted_id` 留空（等同于"手动记录/非 Bank Process 来源"，不影响金额和账本正确性，只是少了"这笔钱是从哪次过账来的"这个溯源链接）。

---

## 关于孤儿数据的共同结论

以上大部分跳过的数据都指向同一个根因：**旧库在"公司删除归档"功能（`company_deletion_archive`）上线之前（2026-06 之前）就已经发生过的公司/流程删除，没有留下任何身份信息**。这不是这次迁移脚本的问题，是旧库本身遗留的历史数据缺口，除非你手上有更早的备份能找回这些公司当时的身份信息，否则这部分不可恢复。
