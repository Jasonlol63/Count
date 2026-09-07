# 2026-09-05 增量同步 + 完整性核对记录

> 本次用的旧库导出:`D:\Backup - c168.net\c168_net-202609051412.sql`(2026-09-05 14:12)。
> 对应脚本:`migrate_delta_identity_tenant_20260905.sql` / `migrate_delta_currency_domain_20260905.sql` /
> `migrate_delta_process_20260905.sql` / `migrate_delta_datacapture_20260905.sql` /
> `migrate_delta_bank_process_20260905.sql` / `migrate_delta_transactions_and_accounting_due_20260905.sql`
> (+ `..._part2.sql`,修正原脚本一个 COLLATE 报错后补跑的部分)。
> 本文档记录:这次同步了什么、明确跳过了什么、以及之后应你要求对 C168/95/AG 三家公司做的完整性抽查结果。

---

## 1. 本次同步的内容(对比 2026-09-03 状态)

| 表 | 新增行数 |
|---|---|
| account | 1 |
| account_currency | 4 |
| account_link | 2 |
| process_submitted | 22 |
| data_captures / data_capture_line | 22 / 126 |
| data_capture_formula | 30 |
| bank_process | 6 |
| bank_process_resend_daily_guard | 22 |
| transactions | 221(金额合计 747,833.74,旧库新库分毫不差) |
| transactions_deleted | 111 |
| bank_process_accounting_posted | 23(去重后) |

跑完核对过:所有涉及表都无重复 id、无重复业务槽位(tenant+bank_process+日期+周期),金额合计对得上。

---

## 2. 明确跳过的部分(经你确认,按"只做新增,不追溯撤销"处理)

### 2.1 Transactions 域:81 条

旧库在 2026-09-03 之后把这 81 条交易撤销(移进了旧库自己的 `transactions_deleted` 归档表),但 `count_real` 里还留着对应记录(其中 80 条是 9/3 那轮迁移进去的、1 条是最早 8/27 全量迁移进去的)。抽样 3 条:

| 旧库 transaction_id | 公司(company_id / tenant code) | 描述 | 金额 | 交易日期 | 旧库撤销时间 | count_real 对应 id |
|---|---|---|---|---|---|---|
| 18474 | company_id 325 / tenant code `23`(不是 C168/95/AG 这三家) | CLAIM FROM CR22 | 200.00 | 2026-08-25 | 2026-09-03 05:01:29 | 18474 |
| 19850 | 同上 | CLAIM FROM BANK | 279.99 | 2026-09-01 | 2026-09-03 06:28:52 | 19850 |
| 19869 | 同上 | Process: Buy Price for PAPPA 34 ENTERPRISE (resend consolidated) | 375.00 | 2026-08-31 | 2026-09-03 07:16:48 | 151477(重新分配的新 id) |

经你确认:这批是用户自己在旧系统里正常删除操作产生的,不是迁移或数据错误,**保留现状,不做撤销同步**。

### 2.2 过账/忽略域:29(process_accounting_posted)+ 59(process_accounting_due_dismissed)条

全部集中在 **company_id 325(tenant code `23`,同样不是 C168/95/AG 这三家)**,2026-09-02 那次批量操作(月结重新合并/resend consolidated),9/3 快照抓到、已迁移进 count_real,9/5 这次又被旧库进一步替换/撤销。抽样:

| 域 | 旧库 id | bank_process_id | 日期 | 类型 | count_real 对应 id |
|---|---|---|---|---|---|
| posted | 2754 | 599 | 2026-08-31 | RESEND_CONSOLIDATED / POSTED | 2754 |
| posted | 2758 | 602 | 2026-08-31 | 同上 | 2758 |
| dismissed→posted(SKIPPED) | 135 | 599 | 2026-08-31 | monthly → FULL_MONTH | 2948 |
| dismissed→posted(SKIPPED) | 137 | 602 | 2026-08-31 | 同上 | 2950 |

同样按"只做新增,不追溯撤销"处理,保留现状。

---

## 3. 你反馈的截图问题(AG under C168,SALARY 缺失)核查结果

### 3.1 截图里缺的那条:**已经在这次同步里补上了**

截图对比 `localhost:5173`(count_real)与 `count168.com`(生产旧系统)的 Payment History,C168 公司账号 `AG`(APEX GAMING)在 04/09/2026 少了两条:

- HONG MING SOON,SALARY: 2284.81
- LEW ZHEN CHENG,SALARY: 3851.76

查证:这两条来自 `data_captures.id=20181`(company_id=5 即 C168,capture_date 2026-09-03,process_id=4250 即 SALARY 锚点,created_at 2026-09-04 09:36:02)—— 这条 capture 正好落在 2026-09-03 之后新增的 22 条里,**本次同步已经把它连同 4 条 `data_capture_line`(id 127309-127312,金额跟截图完全一致)一起迁进了 `count_real`**。现在再看 localhost 那边应该已经能看到这两条了(需要重新查询/刷新报表)。

### 3.2 顺手发现一个历史遗留问题(跟这次缺失无关,但一并记录):process 分类

`process.id=4250`(C168 的 SALARY 锚点)在 `count_real` 里 `category` 是 `GAME`,但按 [MIGRATION_LOG.md §5.2](MIGRATION_LOG.md) 的规则,SALARY/BONUS/PROFIT/COMMISSION 这类固定码锚点应该是 `BANK`。抽查了全部 21 个同类锚点(C168/95/AG/CX/M2/RS/1039 这几家),**全部都还是 GAME,没有一个是 BANK**——说明 §5.2 记录的"直接把 category 改成 BANK"这个修复,实际上并没有在当前 `count_real` 里生效(可能是文档记录了方案、但从没真正执行,或者执行后被后续操作覆盖了)。

**这个问题目前没有导致数据丢失或显示缺失**(截图对比过,GAME 分类下 SALARY 记录本身该显示的都显示了),纯粹是分类字段跟文档规则不一致,要不要现在改需要你决定——涉及 21 条 process 记录,改了以后如果有任何业务逻辑依赖 `category='BANK'`(比如权限、报表分类),需要一并确认影响。

**【已处理,见 §7】** 你已确认要改,§7 记录了具体执行结果。

### 3.3 大范围排查 C168 / 95 / AG 三家:一开始像是发现 205 条"缺失"交易,后来查明全部是误报

用"account_id + amount"反查 `count_real.transactions`,一开始显示 C168 51 条、95(tenant `95`)94 条、AG 60 条"查不到"。深入查证后发现:**这些都不是真的缺失**,而是命中了两个你们之前已经做过的、有意为之的一次性数据修正脚本:

- [`fix_domain_fee_commission_account_direction_swap.sql`](fix_domain_fee_commission_account_direction_swap.sql) —— C168 的 domain fee / commission 交易,`account_id`/`from_account_id` 两列做过对调(为了让 Payment History 的 Cr/Dr 方向跟旧系统一致)
- [`fix_migrated_rate_leg_account_direction_swap.sql`](fix_migrated_rate_leg_account_direction_swap.sql) —— 95/AG/CX/RS/BK1 这几家的 RATE 交易(364 条),同样做过 `account_id`/`from_account_id` 对调

举例验证:旧库 transaction_id=19335(C168,Pay Domain Fee,account_id=4837)在 `count_real` 里 id 同样是 19335,但 `account_id` 是 **5678**(对调后的值),所以我最初按 account_id 直接匹配查不到,其实数据一直都在。同理 transaction_id=17954(95,RATE)在 count_real 里 account_id/from_account_id 也是对调过的。

**结论:C168 / 95 / AG 这三家目前没有发现真正的数据缺失**——`transactions`、`data_captures`/`data_capture_line` 两个核心账本域,行数、金额都对得上,唯一一个真实缺口(3.1 提到的那两条 SALARY)已经在这次同步里补齐了。

---

## 4. 待你决定的事项(§1-3 阶段,已全部处理完,见下方 §5-9)

1. ~~§3.2 的 process 分类问题~~ → 已处理,见 §7。
2. `c168_net_legacy_20260905` / `c168_net_legacy_20260903` 两个临时库,你要求先不删,目前还在本地。

---

## 5. 撤销记录复原(用户误删,非迁移问题)

95 公司(tenant_id=2)3 月 16 日 RATE 交易,你在维护页面误删了两组、共 4 条,已全部复原:

| 旧库 transaction_id | 描述 | 金额 | 交易日期 | count_real 复原后 id | 备注 |
|---|---|---|---|---|---|
| 2915 | Transaction to JB-XIONG (Rate: 1.713) | 5,540.98 | 2026-03-16 | 2915 | 旧库本来就没有 RATE 分组,独立流水 |
| 2917 | Rate charge (x0.033) from CNY 3298.20 | 108.84 | 2026-03-16 | 2917 | 同上 |
| 3271 | Transaction to XE (Rate: /1.703) | 3,298.20(CNY) | 2026-03-16 | 3271 | RATE 分组 `RATE_1773841967_5105` 的 leg1 |
| 3272 | Transaction to XE (Rate: /1.703) | 1,936.70(MYR) | 2026-03-16 | 3272 | 同一分组的 leg2,对手方 JB-XIONG |

`transactions_rate` 表头(`RATE_1773841967_5105`,leg1=3271/leg2=3272,汇率 0.5872,CNY→MYR)也重新建好并回填了两条交易的 `rate_group_id`。

---

## 6. Data Capture 明细行两个字段错配(这次同步新引入,已修复)

写 `migrate_delta_datacapture_20260905.sql` 时照抄了原始迁移脚本的写法,踩上了原脚本两个已知但从未真正改正的 bug(`MIGRATION_LOG.md`/`DATA_CAPTURE_LINE_CURRENCY_FIX_LOG.md` 里都记录过"以后重跑迁移要注意"):

### 6.1 `transaction_id` 从未生成(126 条全部,已修复)

新版 Payment History 读的是 `transactions` 表,不是 `data_capture_line`——这 126 条明细行迁移时没有同步生成对应的 WIN/LOSE `transactions` 记录,导致金额"看不见"。已用 [`migrate_delta_datacapture_line_transactions_backfill_20260905.sql`](migrate_delta_datacapture_line_transactions_backfill_20260905.sql) 补建。你最早反馈的 RS 公司 BZA-312 账号 9/3 那 5 条记录就在这批里:

| data_capture_line id | capture_id | 账号 | 金额 | 生成的 transaction id |
|---|---|---|---|---|
| 127333 | 20182 | BZA-312 | 2163.90 | 151626 |
| 127349 | 20183 | BZA-312 | 1874.36 | 151642 |
| 127352 | 20184 | BZA-312 | 18.17 | 151645 |
| 127355 | 20185 | BZA-312 | -41.36 | 151648 |
| 127358 | 20186 | BZA-312 | 23.28 | 151651 |

全库范围:126 条全部补建完,`data_capture_line.transaction_id` 现在 0 条为空(全库 77,985 条核对过)。

### 6.2 `currency_id` 抄错(30 条,已修复)

这 30 条本该用明细行自己的结算货币(MYR),迁移脚本错抄成了批次头的货币(AUD/PGK/USD/HKD——API 原始计价货币),导致按 MYR 筛选时整行"消失"。已修复,`transactions.currency_id` 同步改过来:

| data_capture_line id | capture_id | 账号 | 金额 | 原货币(错) | 改成 |
|---|---|---|---|---|---|
| 127313-127331(19条) | 20182 | V5 | 各不相同 | AUD(262) | MYR(177) |
| 127332 | 20182 | API SCR888H5 | 43.28 | AUD(262) | MYR(177) |
| 127333 | 20182 | BZA-312 | 2163.90 | AUD(262) | MYR(177) |
| 127350-127352 | 20184 | V5/API SCR888H5/BZA-312 | -18.53 / 0.36 / 18.17 | PGK(358) | MYR(177) |
| 127353-127355 | 20185 | V5/API SCR888H5/BZA-312 | 42.18 / -0.83 / -41.36 | USD(173) | MYR(177) |
| 127356-127358 | 20186 | V5/API SCR888H5/BZA-312 | -23.75 / 0.47 / 23.28 | HKD(176) | MYR(177) |

全库范围核对:77,985 条 `data_capture_line` 逐条按正确货币比对,现在 0 条错配(含 2026-09-04 那次修过的 2,946 条老数据 + 这次的 30 条)。

---

## 7. Process 分类修正(GAME → BANK,已处理)

按你的确认,21 个 SALARY/BONUS/PROFIT/COMMISSION 锚点(C168/95/AG/CX/M2/RS/1039 共 7 家公司)`category` 从 `GAME` 改成 `BANK`,连带 47 条已迁移的 `data_captures.category` 也同步改了。

⚠️ 提醒过的风险:这 21 个 process 名下还有 118 条 `process_day`、21 条 `process_description_link`、31 条 `process_submitted`,只改了 `category` 字段,没动这些关联记录——如果这几家公司的维护页面切类别后排班/提交记录显示异常,回来找我。

---

## 8. 顺手排查全部历史修复脚本,又修复两个同类问题

按你的要求,把 `SqlEtcForMigrate` 目录里所有一次性修复脚本/工具都过了一遍,检查这次新增数据有没有踩上同样的坑:

### 8.1 PAYMENT/CLAIM/CLEAR/CONTRA 描述单边格式(全库 102 条,已修复)

`fix_manual_transfer_description_two_sided_format.sql` 本来是幂等脚本,重新跑了一遍,全库范围内(不只是这次新增的)102 条"只有 FROM 没有 TO"的描述改成了双边格式,例如:

| 交易 id | 改前 | 改后 |
|---|---|---|
| 151378 | PAYMENT FROM BANK | PAYMENT FROM BANK TO SU24 |
| 151379 | CLAIM FROM BANK | CLAIM FROM BANK TO EXPENSES |
| 151385 | CLAIM FROM CR22 | CLAIM FROM CR22 TO SU27 |

### 8.2 Bank Process 过账交易描述(13 条,已修复)

编译运行了 `BankProcessDescriptionBackfillTool.java`(先 preview 后 apply),全库 1,005 条相关交易里,13 条(tenant 6/CX 3 条、tenant 18 7 条 + 3 条同批)描述还是旧库原始文字,改成了新系统自己的格式:

| 交易 id | 改前 | 改后 |
|---|---|---|
| 151407 | Process: Profit for ZC DREAM HOME FURNITURE (resend consolidated) [RESEND_END=2026-09-30] | FULL MONTH (SEP 2026) @MONTHLY 800 \| CIMB |
| 151593 | Process: Buy Price for DISTINCT DESIGN RETAIL PTE. LTD. | MONTHLY BILL 2650 \| OCBC |
| 151594 | Process: Sell Price for DISTINCT DESIGN RETAIL PTE. LTD. | MONTHLY BILL 3300 \| OCBC |
| 151595 | Process: Profit for DISTINCT DESIGN RETAIL PTE. LTD. | MONTHLY BILL 650 \| OCBC |

（其余 9 条同一批次,细节见 `backfill_apply.txt`。）

**顺带发现、但不属于这次范围的问题**:同一个工具跑出来另外 75 条(tenant 24)因为 `bank_process_accounting_posted.billing_start/billing_end` 缺失报错跳过——查过时间戳,这些是 9/3-9/4 之间**活跃用户在系统里自己新建的真实数据**,不是这次迁移产生的,也不是这次同步的公司,不在我这次的处理范围,报给你知道。

### 8.3 检查过、确认跟这次新数据无关的(未改动)

- `data_capture_line.id_product` 空值回填:这次新增 126 条,0 条空值
- RATE 腿方向对调 / self-reference 修复:这次没有新增 RATE 分组
- process 真重复合并:这次没有新的重复 process
- tenant_auto_renew / user_group_map 补录:这次没有新用户/新自动续费记录
- 其余几个只锁定具体历史 id 的一次性修复:范围跟这次新数据不重叠

---

## 9. Bank Process Maintenance"已删除"列表看不到旧数据——根因是 `bank_process_posted_id` 从未回填(不是这次才有,是历史遗留)

### 9.1 现象

你在对比"23"公司的 Bank Process Maintenance 页面时发现:旧系统(count168.com)能看到一批 2026-08-12 删除的记录(SERENA MALA ENTERPRISE / HX MOBIX ENTERPRISE / SHACHA BIZ TRADING),新系统这个页面完全看不到,显示的是完全不相关的其他记录,行号编号也对不上。

### 9.2 根因

`transactions_deleted` 表里的 `bank_process_posted_id` 字段,**从最早的全量迁移、9/3 delta、到这次 9/5 delta,没有任何一版脚本填过**——底层数据本身没丢(旧库 company 325 一共 400 条已删除记录,`count_real` 也是 400 条,一条不少),但 `MaintenanceMapper.xml` 的 `findBankProcessMaintenanceDeletedRows` 这条查询写死了 `td.bank_process_posted_id IS NOT NULL`,只要这个字段是空的,页面直接把整条记录过滤掉,不会显示。

数据库层面核实:全库范围(不只是"23"公司)WIN/LOSE 类型的已删除记录里,886 条这个字段是空的,横跨 8 个 tenant(23 有 384、CX 195、72(tenant24) 159、M2 128、AG/95/RS/M1 各几条)。

### 9.3 已经处理

写了 [fix_transactions_deleted_bank_process_posted_id_backfill.sql](fix_transactions_deleted_bank_process_posted_id_backfill.sql),用每条已删除记录自己在旧库里的 `source_bank_process_id`/`source_bank_process_period_type` 反查出正确的过账记录(业务字段匹配,不依赖任何数字 id 的巧合),分两轮跑完:

- 第一轮:精确匹配(tenant+bank_process+过账日期+周期类型全部对上),补了 **581 条**
- 第二轮:同一个 (tenant, bank_process, 过账日期) 组合下只有唯一一条候选时也认,补了 **38 条**
- 合计修复 **619 条**,"23"公司从 384 条缺失降到只剩 4 条,SERENA MALA / HX MOBIX / SHACHA BIZ TRADING 已核实全部正确关联

### 9.4 剩余 267 条,查到具体是哪个 `bank_process_id`,但没法修

| 类别 | 条数 | 原因 |
|---|---|---|
| bank_process 本身在旧库已被整条删除 | 约 228 | 涉及 tenant 72(如 700/702-708/709/711/713/714/716/731/747/748/756 等 id)、CX(583/586/553-556/564/572/695/696)、M2(613)——这些 id 在当前旧库导出里完全查不到,链路从源头断了,无法追溯复原 |
| bank_process 还在,但那一天的过账记录本身就没建过 | 约 39 | CX 的 189/420、M2 的 694/689/701/676——直接查证:旧库当前这份导出里,`process_accounting_posted` 和 `process_accounting_due_dismissed` 两张表,那个具体日期的记录**一条都不存在**(比如 M2 的 676 需要 2026-04-30 这天,旧库只有 2024-04-20/2025-12-31/2026-03-31/2026-09-30 这几天的记录)。**核实过跟 §10 的串号 bug 无关**——不是接错线,是源头这根线在旧库那头已经断了,跟上一行"bank_process 整条被删"是同一性质,只是精确到了具体某一天,同样无法追溯复原。 |

### 9.5 这剩余 267 条不修复,会有什么影响?

**核实过:不会影响任何余额、流水金额。** 全代码库搜索确认,`transactions_deleted` 这张表**只有 `MaintenanceMapper.xml` 一处引用**,Payment History 余额、Transaction 流水 Cr/Dr、各类报表汇总全部只读活的 `transactions` 表,不会读 `transactions_deleted`——这些已删除记录本来就不参与任何实时账本计算。

唯一受影响的是 `findBankProcessMaintenanceDeletedRows`(Bank Process Maintenance 页面"已删除"这个 Tab)要求 `bank_process_posted_id IS NOT NULL` 才显示,这 267 条因为源头数据在旧库已经彻底不存在、没法回填,**永远不会出现在这个"已删除"列表里**——影响面仅限于"翻查这几笔历史删除记录的审计追溯"这一项,不涉及任何金额对错。真正会影响金额/流水的是 §10 那 34 条(活的交易挂错银行合约),不是这 267 条。

---

## 10. `bank_process_posted_id` 关联串号 —— 这次脚本自己的 bug,导致 34 条交易被挂到不相关的银行合约名下

### 10.1 根因

`migrate_delta_transactions_and_accounting_due_20260905.sql`(含 part2)第 6 步,给新增交易回填 `bank_process_posted_id` 时用的逻辑:

```sql
bap.id = COALESCE((SELECT new_id FROM _new_bap_map WHERE legacy_id = lp.id), lp.id)
```

意思是"如果这条旧库过账记录不是这次(9/3→9/5)新增的,就直接把旧库自己的数字 id 当 count_real 的 id 用"。**这个假设是错的**——"不是这次新增的"不等于"id 从旧库到 count_real 是 1:1 保留的"。"23"公司(tenant 18)旧库那边反复做"月结重新处理(resend consolidated)",很多"看起来不是这次新增"的过账记录,其实是更早一轮(9/3 delta)就已经被重新分配过 id 的——它们在旧库里的原始数字 id,现在在 count_real 里对应的是完全不相关的另一条记录,我这次脚本用 auto-increment 新建记录时又凑巧用到了同一个数字,导致张冠李戴。

### 10.2 举例验证

| 项目 | 内容 |
|---|---|
| 旧库交易 | legacy id 20285,THE QIN RESTAURANT 的重复过账 leg,resend_consolidated_range,2026-08-27 |
| 旧库过账记录 | id=2979,process_id=691(THE QIN RESTAURANT) |
| count_real 里 id=2979 实际是 | bank_process_id=639,**HX MOBIX ENTERPRISE** 的一条"月结跳过"记录——完全是另一家公司 |
| 结果 | count_real 交易 id=151453 被错误挂到 HX MOBIX 名下,§8.2 的描述修复工具又照着这个错误关联把描述文字重写了一遍,进一步掩盖真实来源 |

### 10.3 影响范围

全库排查(用"该笔交易的账号是否在关联银行合约的供应商/客户/公司/分成名单里"这个条件筛查),**只有"23"公司(tenant 18)受影响,共 34 条**,涉及 TRAVELMINI SDN BHD、CARGO SOLUTIONS PTE LTD、JJ TECH COMPUTER、KEDAI PAKAIAN SUSIAMAK、FLORA LUXE ENTERPRISE、HX MOBIX ENTERPRISE、SERENA MALA ENTERPRISE、VPA TRADING 这几个银行合约之间互相串号。其他公司没有触发这个 bug,因为只有"23"公司旧库那边有大量 resend 重新处理活动。

### 10.4 解决方案(还没执行)

1. 这 34 条交易的 `bank_process_posted_id`,改用跟 §9.3 一样的思路重新解析——按每条交易自己的 `tenant_id + source_bank_process_id(旧库原始记录自带,不受这个 bug 影响)+ posted_date + period_type` 去 `bank_process_accounting_posted` 里重新匹配真正对应的那一条,不再信任任何数字 id 的巧合
2. 这 34 条里被 §8.2 描述修复工具"美化"过的,要重新按正确的银行合约生成描述,不能保留现在挂错公司的文字
3. 需要顺手确认 9/3 那一轮 delta(用的是同一套 COALESCE 逻辑)是不是也有同样的问题,还没查

**这一节的 34 条目前还没有修复,等你确认后再动手。**

---

## 11. 你反馈"23"公司多出几笔手动 CONTRA + bank process 记录——查证结果:两个都是已知问题的具体案例,不是新 bug

用 CR20、CR1(MARIOE)两个账号的 Payment History 截图(count_real vs `c168_net_legacy_20260905`)逐笔核对:

### 11.1 CR20 多出的那笔 CONTRA(75,02/09)—— 属于 §2.1 已批准跳过的类别,不是新问题

- 旧库交易 id=20118「CONTRA FROM CR20」(CR20→BANK,75,交易日 09-01,remark: SERVICE CHARGE AUG'26),在 9/3 那轮迁移进了 `count_real`(同 id 20118)。
- 旧库在 2026-09-03 06:30:43 把这笔**软删除**了(`transactions_deleted.transaction_id=20118`),同时在 09-02 新建了一笔方向相反、更正过的 CONTRA(旧库 id=20301「CONTRA FROM BANK」,BANK→CR20,75)。
- 这次 9/5 delta 正确地把新的那笔迁移了进来(count_real id=151464)。但按 §2.1 已经确认的"只做新增,不追溯撤销"原则,旧的 20118 没有被撤掉,所以 `count_real` 里现在**两笔都在**(20118 + 151464),而旧库现在只剩新的一笔——这就是截图里"新系统多一行 CONTRA"的原因。
- **结论:这是 §2.1 那 81 条已批准跳过类别里的一个具体案例(旧库自己撤销重开的单据),机制上不是新 bug**。之前 §2.1 只说"保留现状不影响什么",这次具体验证到:像这种"旧的被删、同时有新的补上"的情况,两笔都会同时留在活的 `transactions` 表里,是会实际叠加进账本余额的(不是纯审计问题),需要你知道这一点、确认是否还要维持"不追溯撤销"这个决定。

### 11.2 CR1(MARIOE)多出的那笔 KEDAI PAKAIAN SUSIAMAK 750—— 确认就是 §10 那 34 条串号 bug 里的两条

- count_real id=151482 / 151486(均为 LOSE 750,同一天 2026-08-31,描述都是"KEDAI PAKAIAN SUSIAMAK (resend consolidated)"),`bank_process_posted_id` 分别指向 3012 / 3014。
- 查证:3012、3014 这两条 `bank_process_accounting_posted` 记录的 `tenant_id` 都是 **6**,不是 18(bank_process_id 分别是 189、526,跟 KEDAI PAKAIAN SUSIAMAK 毫无关系)——跟 §10.2 举的例子是同一个根因(`COALESCE(..., lp.id)` 张冠李戴)。
- 这两条已经包含在 §10.3 统计的 34 条里,**不是新发现的额外数量,是同一批问题的其中两条实例**,修复方案维持 §10.4,等你确认。

### 11.3 顺手复查:确认 §2.1 的 81 条跳过记录,全部有旧库归档记录对应(不是数据无故消失)

用 `transaction_id`(而不是误用 `transactions_deleted` 自己的自增 `id`)重新核对了一遍:9/3→9/5 快照之间,company_id 325 这边"消失"的交易,**100% 都能在旧库 9/5 的 `transactions_deleted` 归档表里用 `transaction_id` 关联到**(deleted_at 都在 09-02~09-04 之间)。之前 §2.1 写的"81 条"确认无误、没有遗漏或误报,这次没有发现"旧库数据凭空消失、连归档表都没有"这种更严重的情况。

**本节结论:目前为止,"23"公司暴露出的所有差异,都归到已经记录过的 §2.1(已批准跳过)或 §10(34 条串号 bug,待你确认修复)这两类里,没有发现新的第三类问题。§10 的修复方案见上面 §10.4,先不动代码/数据,等你决定。**

---

## 12. §2.1 那 81 条清理:5 条无歧义的已处理,其余 76 条按你要求"你发现了我再改"

### 12.1 尝试精确定位全部 81 条,发现大部分有歧义,没法安全批量处理

想按"金额 + 日期 + 账号"把 81 条旧库已撤销的记录跟 `count_real` 里对应的活跃记录一一匹配,结果发现:**旧库的"resend consolidated"(月结重发)业务本身会对同一笔真实费用反复生成好几条金额、日期、账号完全相同的记录**(比如 KEDAI PAKAIAN SUSIAMAK 那笔 750,`count_real` 里现在有 5 条金额日期账号都一样的候选;§10 那 34 条串号 bug 想反查正确银行合约时同样遇到,同一个账号在几十个合约里都是分成方,金额还经常重复)。

根本原因:9/3、9/5 两轮迁移时用来记录"旧库 id ↔ count_real id"对应关系的临时表(`_new_txn_map`)是一次性的,脚本跑完自动删了,现在没有留存,只能靠"金额+日期+账号"反推——但业务内容本身有大量重复,反推会有歧义,选错了会把一笔真实的、不同银行合约下的交易误删。

**能 100% 确认对应关系、没有歧义的只有 5 条**(18474、19850、20011、20012、20118——CR20 那笔 CONTRA 就在这 5 条里)。

### 12.2 已处理:5 条无歧义记录

用 [fix_tenant18_stale_superseded_transactions_cleanup.sql](fix_tenant18_stale_superseded_transactions_cleanup.sql) 处理:每条先按旧库自己的 `deleted_at`/`deleted_by`(owner K23)迁入 `transactions_deleted`(保留审计痕迹),再从活的 `transactions` 表删除。

| count_real id | 类型 | 金额 | 交易日期 | 账号 |
|---|---|---|---|---|
| 18474 | CLAIM | 200.00 | 2026-08-25 | CR22→SU4 |
| 19850 | CLAIM | 279.99 | 2026-09-01 | BANK→SA6 |
| 20011 | WIN | 1,125.00 | 2026-08-31 | CR19 |
| 20012 | LOSE | 1,400.00 | 2026-08-31 | SU20 |
| 20118 | CONTRA | 75.00 | 2026-09-01 | CR20→BANK |

执行时发现一个小插曲、已经当场修正:插入 `transactions_deleted` 时才发现这 5 条其实**已经各自有一条对应的归档记录了**(id 2104/2191/2154/2155/2192,是 9/3 那轮迁移时就正常同步过去的——说明"活跃交易"和"已删除归档"这两份记录一直是同时存在的,缺的只是活跃那份没被清掉)。我最初没检查就直接插入,导致每条变成 2 份重复归档记录,当场发现后立刻删除了刚插入的那 5 份重复(id 2233-2237),只保留原有的归档记录。现在核实过:`transactions` 表里这 5 条 id 已经 0 条,`transactions_deleted` 里对应的归档记录还是原来那 5 条,没有重复。

### 12.3 剩余 76 条(+34 条 §10):按你的要求,你发现具体哪笔账对不上,我再逐笔核实处理

这部分暂不批量处理。你在核对新旧系统账目时,如果看到某个账号/日期/金额对不上,把截图或具体信息给我,我按 CR20、CR1 这两笔的方法(用账号+金额+日期+关联的银行合约信息逐笔反查旧库源头)去核实、确认后再改,避免批量歧义匹配出错。

---

## 13. §10 串号 bug 彻底修复:找到了精确的反推方法,70 条重新关联 + 72 条旧记录清理,已全部执行完

### 13.1 方法突破:精确复原了"旧库 id ↔ count_real id"的对应关系

之前(§10.4)提议按"账号+金额反查银行合约"去修复,但那个方法在候选记录太多时会有歧义,没敢直接用。后来在核查 23 GROUP/SU25/CR19 这几个账号时,找到了一个可以完全复原对应关系的办法:

**重新按 9/5 那一轮脚本(`migrate_delta_transactions_and_accounting_due_20260905_part2.sql`)当初用的 `ROW_NUMBER() OVER (ORDER BY t.id)` + `base_txn=151377` 逻辑,重建出跟当时一模一样的临时映射表** —— 用几个已知的具体案例反复验证过(比如 count_real id=151477 精确对应旧库 id=20317,不是之前 §2.1 误记的 19869),完全可靠。

有了这个精确映射,再用每一条交易自己在旧库的 `source_bank_process_id` 反查正确的银行合约,**不再需要靠"金额+账号"去猜候选**。

### 13.2 完整核实结果:9/5 这一轮 100 条 tenant 18 交易里,70 条挂错,72 条对应的旧记录已作废

- 70 条:`bank_process_posted_id` 挂错(或没挂),按各自的 `source_bank_process_id` + 交易日期 + `RESEND_CONSOLIDATED` 精确反查到正确的银行合约 —— **全部 18 组、每一条都验证过,零歧义**(具体清单见下方 §13.3)。
- 72 条:是同一批银行合约"上一轮重发"时已经迁移进来、旧库自己后来又撤销掉的旧记录(部分合约像 THE QIN RESTAURANT 甚至有两代旧记录都作废了)——跟 §2.1/§12 是同一类"旧库已撤销、count_real 还留着"的情况,用 `source_bank_process_id` 精确核对过,零歧义。

**上一轮我曾经说"SU25/23 GROUP/CR22 那 12 条重复该删",这个是错的,已经在这次一并纠正**——那 12 条其实是当前有效记录、只是挂错了合约,已经按正确答案重新关联,没有删除;真正该删的是它们对应的、更早两代的旧记录。

### 13.3 执行内容(已完成)

1. [fix_tenant18_bank_process_posted_id_relink_20260905round.sql](fix_tenant18_bank_process_posted_id_relink_20260905round.sql) —— 70 条 `bank_process_posted_id` 重新关联,执行前后各做了行数核对(应该是 70 条,实际更新 70 条)。

   | 正确合约 | 正确公司 | 修复前误挂 | 条数 | count_real id |
   |---|---|---|---|---|
   | 599 | PAPPA 34 ENTERPRISE | (未关联) | 4 | 151477-151480 |
   | 602 | KEDAI PAKAIAN SUSIAMAK | TRAVELMINI SDN BHD | 4 | 151481-151484 |
   | 603 | KEDAI PAKAIAN SUSIAMAK | CARGO SOLUTIONS PTE LTD | 4 | 151485-151488 |
   | 604 | JACK COMPUTER TRADING | (未关联) | 5 | 151489-151493 |
   | 605 | JACK COMPUTER TRADING | (未关联) | 4 | 151494-151497 |
   | 617 | KW PETS TRADING | TRAVELMINI SDN BHD | 4 | 151390-151393 |
   | 629 | MODA HOUSE ENTERPRISE | KEDAI PAKAIAN SUSIAMAK | 4 | 151418-151421 |
   | 646 | YK LAI SOURCES | (未关联) | 3 | 151465-151467 |
   | 647 | YK LAI SOURCES | TRAVELMINI SDN BHD | 3 | 151468-151470 |
   | 648 | YK LAI SOURCES | (未关联) | 3 | 151471-151473 |
   | 660 | ZC DREAM HOME FURNITURE | JJ TECH COMPUTER | 3 | 151405-151407 |
   | 661 | ZC DREAM HOME FURNITURE | JJ TECH COMPUTER | 3 | 151408-151410 |
   | 669 | VPA TRADING | (未关联) | 5 | 151503-151507 |
   | 670 | VPA TRADING | (未关联) | 5 | 151508-151512 |
   | 691 | THE QIN RESTAURANT | HX MOBIX ENTERPRISE | 4 | 151451-151454 |
   | 692 | THE QIN RESTAURANT | FLORA LUXE / SERENA MALA | 8 | 151441-151444, 151455-151458 |
   | 693 | THE QIN RESTAURANT | VPA TRADING | 4 | 151459-151462 |

2. [fix_tenant18_superseded_bank_process_predecessors_cleanup.sql](fix_tenant18_superseded_bank_process_predecessors_cleanup.sql) —— 72 条旧记录清理。执行前确认过这 72 条**全部已经各自有一份归档记录**(避免重演 §12 那次重复插入的失误),所以这次脚本只做了"从活的 `transactions` 删除",没有再插入 `transactions_deleted`。执行前后行数核对都是 72,无误。

3. 重新跑了 `BankProcessDescriptionBackfillTool`(--tenant=18 --apply):243 条相关交易里 69 条描述文字改回正确公司名(撤销了 §8.2 那次因为挂错关联而写错的文字),164 条本来就对不需要改,10 条因为工具自身的已知限制(账期字段缺失导致的计算异常/账号不在供应商客户分成名单里)没法重新生成——但这 10 条**旧的描述文字本来就写对了公司名**,核实过不影响数据正确性。

4. **最终核对:tenant 18 范围内,`transaction_date` 与其关联的过账记录 `posted_date` 不一致(即挂错的信号)的记录数 = 0。** §10 这个 bug 到这里彻底清干净了。

**§10.4 里"9/3 那轮是否有同样问题还没查"这一点**:这次的 70+72 条全部来自 9/5 这一轮(`migrate_delta_transactions_and_accounting_due_20260905_part2.sql`);9/3 那一轮的映射表因为当时的临时表已经drop、且推算 base_txn 时发现跟已有记录对不上(细节见前面 §12.1 的分析),暂时没法用同样精确的方法复原,仍然是待办,不在这次范围内。

---

## 14. "23" company 8 月份出现莫名 B/F 金额——查到是 2024 年的老账,已清理

### 14.1 现象

你在 Contra Inbox 页面对比新旧系统时发现:8 月份报表(账期本该从 8 月开始,之前不该有任何数据)里,新系统好几个账号(CR22、CR6、SU2、SU23、23 GROUP、AG1)的 B/F 不是 0,旧系统对应位置全是 0.00。

### 14.2 查证

`count_real` 里有 7 条 **2024 年**的老记录(id 151302-151308),对应两个早就过期的银行合约:

| 银行合约 | 公司 | 涉及账号(金额) | 日期 |
|---|---|---|---|
| 599 | PAPPA 34 ENTERPRISE | SU2(2,239.11)、CR6(4,776.77)、23 GROUP(1,791.29)、AG1(746.37) | 2024-06-27 |
| 663 | J & J AUTO GARAGE | SU23(2,984.95)、CR22(5,074.41)、23 GROUP(2,089.46) | 2024-11-28 |

跟截图里的金额、账号完全对上(23 GROUP 两笔合计 3,880.75)。

查旧库:这两个合约的 `bank_process_accounting_posted` 过账记录(1891、2350)状态还是 POSTED,但对应的实际交易记录,旧库这边查了完整历史,**一条活的都没有**——查到过两代(合约 599 是 id 18239-18242 和 19082-19085;合约 663 是 id 18255-18257 和 19250-19252),全部都已经被旧库自己删除(deleted_at 2026-09-02,owner K23)。

跟 §2.1/§12/§13 是同一个模式("旧库已撤销,count_real 还留着"),只是这次撤销发生在比 9/3→9/5 这个排查窗口还要早的时间点,是最早 8/27 全量迁移进来的历史遗留,之前几轮增量同步的对比都扫不到这里。

### 14.3 已处理

用 [fix_tenant18_2024_stale_resend_charges_cleanup.sql](fix_tenant18_2024_stale_resend_charges_cleanup.sql) 处理:这 7 条**没有**像 §12/§13 那样已经有对应的归档记录(检查过,0 条),所以这次是先插入 `transactions_deleted`(用旧库的 deleted_at/deleted_by 信息),再从活表删除。执行前后核对都是 7 条,无误。

**最终核对:tenant 18 范围内,`transaction_date` 早于 2026-08-01 的活跃交易记录数 = 0。** 8 月份 B/F 异常的问题到这里解决。

---

## 15. CR10/AG4/SU13 的 MODA HOUSE 多一条 + SU25 两条"DATA CAPTURE"——用最新旧库导出(2026-09-07 11:49)复查,确认都是旧库自己的问题,不是迁移引入的

用你给的 `D:\Backup - c168.net\c168_net-202609071149.sql` 建了新的临时库 `c168_net_legacy_20260907`(比之前的 9/5 快照更新)重新核对了一遍。

### 15.1 CR10/AG4/SU13 的 MODA HOUSE 多一条:旧库自己就有重复,截至 9/7 这份最新导出仍未解决

银行合约 629(MODA HOUSE ENTERPRISE,BI 银行那条腿)在旧库里同时存在**两条内容完全相同的活跃交易**(账号、金额、日期、`source_bank_process_id` 全部一样,`scope_type`/`approval_status` 也一样,只是创建时间不同:一组 2026-09-02 06:13:10,一组 2026-09-03 05:50:47):

| 账号 | 金额 | 旧库交易 A | 旧库交易 B | count_real 对应 |
|---|---|---|---|---|
| CR10 | 1,433.33 | 19961 | 20246 | 19961 / 151419 |
| AG4 | 100.00 | 19963 | 20248 | 19963 / 151421 |
| SU13 | 500.00 | 19960 | 20245 | 19960 / 151418 |

在 9/7 这份最新导出里,**这两组仍然都是活跃状态,`transactions_deleted` 里也都查不到任何一条被撤销的记录**——说明这不是我们这边快照过时的问题,是旧库自己这个重复到今天(9/7)都还没处理掉。`count_real` 忠实地把旧库这两条都迁移了过来,没有引入新的错误。

**这个问题建议你直接跟旧系统那边核实**(是否是当时人工重复提交、还是系统本身的 bug),确认该保留哪一条、删掉哪一条之后,我再按你的确认执行——目前没有足够依据判断该删 19960/19961/19963 这组还是 20245/20246/20248 这组,不敢瞎猜。

### 15.2 SU25 两条"DATA CAPTURE":旧库自己的账务记录本身就缺了这一段,不是迁移漏挂

银行合约 693(THE QIN RESTAURANT,HLB 银行那条腿)8 月 31 日这笔交易(旧库 id 20278/20281),在旧库自己的 `process_accounting_posted` 表里**从来没有生成过对应的"resend consolidated / 2026-08-31"过账记录**——9/7 这份最新导出核实过,仍然只有 8/27 那一次(已经在 §13 里修复关联好了),31 号这天只有一条 `monthly_skipped`,没有 `resend_consolidated_range`。

也就是说:旧库自己的"交易记录"和"过账记录"这两张表在这一笔上本身就对不上——旧库的交易表说这笔钱来自 693 合约 8/31 的 resend consolidated,但旧库自己的过账记录表压根没建过这条。这不是我们迁移漏挂的锅,是源头数据自己不完整,我们没法凭空编一条过账记录出来补上关联。

**这个也建议你跟旧系统那边核实**,确认这笔账到底应该挂在哪个过账周期下,有明确答案后我再处理。

---

## 16. §15 两个问题:已按你的确认处理完

### 16.1 MODA HOUSE(bp629)重复:已删除较晚的那一批,保留较早迁移的

你确认"23 GROUP"账号下也有同样的多一条问题(跟 CR10/AG4/SU13 是同一批,都挂在 `bank_process_accounting_posted` id=2815 下),并确认要把多出来的去掉。

用 [fix_tenant18_moda_house_bp629_duplicate_cleanup.sql](fix_tenant18_moda_house_bp629_duplicate_cleanup.sql) 处理:4 个账号各一条(CR10/AG4/SU13/23 GROUP),保留较早迁移进来的那一批(id 19960/19961/19962/19963,2026-09-02 迁移),删除较晚的那一批(id 151418/151419/151420/151421,2026-09-03 迁移)——先迁入 `transactions_deleted`(标记 `SYSTEM_DEDUP`,因为旧库自己没有撤销记录可以直接沿用),再从活表删除。执行前后核对都是 4 条,无误。

### 16.2 SU25 两条 THE QIN RESTAURANT 记录:已补建缺失的过账记录

你确认这两条(151446、151449)对应的银行合约 693,resend 周期照旧系统显示的走"9月1日-9月30日"整月(`RESEND_END=2026-09-30`),照着它两个兄弟合约(691→bap 3212、692→bap 2927)同样的格式补建。

用 [fix_tenant18_bp693_missing_bap_backfill.sql](fix_tenant18_bp693_missing_bap_backfill.sql) 处理:新建了 `bank_process_accounting_posted`(tenant=18, bank_process=693, posted_date=2026-08-31, period_type=RESEND_CONSOLIDATED, outcome=POSTED,新 id=3231),把 151446/151449 的 `bank_process_posted_id` 关联过去。

重新跑了描述回填工具——这两条因为 `billing_start/billing_end` 字段缺失(新建的过账记录没有这两个字段值)还是没能重新生成新格式文字,但**旧的描述文字本来就正确显示"THE QIN RESTAURANT"**(因为之前这两条从没被错误关联过、也就没被 §8.2 的工具写错过),所以显示上没有问题,页面现在能正确关联显示了(不再是"DATA CAPTURE"占位文字)。

### 16.3 补充修正:16.2 建的过账记录一开始日期就插错了,PRORATED 格式一直没生成对

你反馈这两条还是原始文字格式("PROCESS: BUY PRICE FOR THE QIN RESTAURANT (RESEND CONSOLIDATED)..."),没有变成其他几条一样的 `PRORATED(1/9 - 30/9 | 30 DAYS)@MONTHLY...` 格式——查了一下,是我自己这边的问题:

1. **建过账记录时 `posted_date` 就填错了一天**:插入用的字面值 `'2026-08-31'`,但对应交易的 `transaction_date` 实际存的是 `'2026-09-01'`(跟兄弟合约 691/692 的过账日期一致)——插进去的这条本身就跟交易日期对不上,是我自己又犯了一次跟 §10 一样的"日期没对齐"问题,已经改正为 `2026-09-01`。
2. **`billing_start`/`billing_end` 两个字段建的时候忘记填了**:描述回填工具算 PRORATED 天数要用到这两个字段,没有就没法算,只能保留原始文字。已经照兄弟合约 692(id 2927)的格式补上 `billing_start=2026-09-01`、`billing_end=2026-09-30`;顺带发现合约 691(id 3212)当初也漏填了这两个字段(所以它的两条也一直是原始文字,只是没被你注意到),一并补齐了。

补完之后重新跑了描述回填工具,151437/151440(合约 691)、151446/151449(合约 693)这 4 条现在都正确显示 `PRORATED(1/9 - 30/9 | 30 DAYS)@MONTHLY 1400 | MBB`(691)和 `... | HLB`(693) 了。

### 16.4 你反馈"23 GROUP"还有两个问题:一个是我自己漏改的,一个是新发现的真实缺口

**问题 1:MODA HOUSE 还多一条**——查了一下不是重复没删干净,是另一个我之前完全没查到的点:银行合约 627(BSN 银行那条腿)的 23 GROUP 分成记录,旧库那边在 **2026-09-03 05:46:58 就已经把它删除了**,而且删除之后没有任何新记录顶替——用 9/7 最新导出确认过,627 这条腿当前在旧库里,23 GROUP 账号名下**一条活的都没有**(628、629 两条腿倒是都还在)。跟 §2.1/§12/§13/§14 是同一个模式(旧库已撤销,`count_real` 还留着,这条本来就已经有归档记录),这次是我自己排查 §13 那 70 条串号 bug 时只查了"挂错合约"的情况,没有覆盖到"合约本身没挂错、但旧库已经把这条删了"这种情况,漏掉了。已经把 count_real 里的这条(id 19954)也删除,现在 23 GROUP 名下 MODA HOUSE 正确显示 2 条(628、629)。

**问题 2:THE QIN RESTAURANT 那条没吃到格式**——这个是我自己 §16.2/16.3 修 693 合约的时候手滑漏改的:那次只顾着看 SU25 账号,只把 151446/151449(SU25 名下的两条)改了,同一批(合约 693 第二次过账)里 23 GROUP(151448)、CR22(151447)这两条也应该一起改,当时漏掉了。现在补上了,`bank_process_posted_id` 都关联到 3231,重新跑描述回填后正确显示 `PRORATED(1/9 - 30/9 | 30 DAYS)@MONTHLY 200/1800 | HLB`。

**最终核对**:tenant 18 范围内 `transaction_date` 与 `bank_process_posted_id` 关联的过账日期不一致(挂错信号)= 0 条。

### 16.5 更正:16.4 问题 1 删错了,已经复原

你反馈旧系统(实时)23 GROUP 账号下这条 MODA HOUSE(合约 627,833.33)其实还在,是我删错了——已经从 `transactions_deleted`(id=2127)原样复原回 `transactions`(id=19954),内容、`bank_process_posted_id`(2811)都跟删除前一致。

**为什么会判断错**:9/7 11:49 那份导出里这条记录显示 `deleted_at=2026-09-03`,但看起来旧库那边后来自己又把它恢复/重新处理过了,而我们 9/7 这份导出没能反映出这个"删除之后又恢复"的后续变化——说明就算有一份比较新的导出,只要旧库那边还在持续变动,静态快照也可能跟"当下实时"的状态对不上,不能完全当作最终答案。

以后遇到类似"旧库显示已删除"的情况,再要动手删之前会更谨慎,尽量拿你截图上实时看到的状态做最终确认,而不是单靠某一份导出的快照。
