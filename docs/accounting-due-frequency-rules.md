# Accounting Due Frequency 业务规则

本文档记录 Bank Process 在 Accounting Due 中的出账规则。修改 Frequency、账期生成、跳过或交易逻辑时，必须同步更新本文档。

## 通用规则

- 只有 `ACTIVE`、`OFFICIAL`、`E_INVOICE` 状态可以生成账单。
- `INACTIVE`、`BLOCK` 等非可出账状态不生成账单。
- `postedDate` 是账单锚点，也是账期唯一键的一部分。
- 已 `POSTED` 或 `SKIPPED` 的账期通过 `bankProcessId + postedDate + periodType` 排除。
- Accounting Due 只返回尚未结算的账期。
- **非当月跳过（对照月 = 合同 `createdAt` 所在自然月）**：`dayStart` 早于创建月时，创建月之前的账期不出；只从创建月起（及之后）展示。例：7 月创建、`dayStart` 在 6 月 → 跳过 6 月，只出 7 月及往后。

## 1st of Every Month

- 需要 `dayStart`、`dayEnd`，按自然月出账。
- 首月从 `dayStart` 开始：
  - `dayStart` 为当月 1 日：`FIRST_MONTH`。
  - `dayStart` 非当月 1 日：`PARTIAL_FIRST_MONTH`。
- 中间完整月份：`FULL_MONTH`，账期为当月 1 日至月末。
- 最后一个月若 `dayEnd` 早于月末：
  - Day end 开关 **ON**（`dayEndMonthlyCapEnabled=true`）→ `DAY_END_TAIL`，账期 `[1st, dayEnd]`（例：9/1–9/9）。
  - Day end 开关 **OFF** → 仍走 `FULL_MONTH`，账期 `[1st, 月末]`（例：9/1–9/30）。
- 首月 `postedDate = dayStart`，之后月份 `postedDate = 当月 1 日`。
- 返回 **所有** `postedDate <= today`、且落在合约月内、**不早于创建月** 的账期（可多笔并列）。
- **非当月跳过**：7 月创建 + `dayStart` 在 6 月 → 跳过 6 月账单，从 7 月起算。
- **未提交保留**：7 月未提交时，到 8 月仍保留 7 月；若 8 月已到 posted day，同时出现 8 月。
- **Delete（Skip）**：删掉某月后该月不再显示；到了下月照常显示已到期的下月。
- **Refresh（restoreSkipped）**：恢复被删月份，不影响其它已在 Due 的月份。
- Edit Process（仅 1st of every month）Day end 旁开关会持久化到 `bank_process.day_end_monthly_cap_enabled`。

### Post to Transaction（1st of Every Month，已实现）

- API：`POST /api/bank-process/accounting-due/post`，body 与 skip 同形：`[{ bankProcessId, postedDate, periodType, billingStart, billingEnd }]`。
- 仅支持 `FIRST_OF_EVERY_MONTH` 的账期类型：`FIRST_MONTH` / `PARTIAL_FIRST_MONTH` / `FULL_MONTH` / `DAY_END_TAIL`。
- 金额来源：
  - **Buy Price**（必拿）→ Supplier，`WIN`
  - **Sell Price**（必拿）→ Customer，`LOSE`
  - **Profit**（必拿，存的是净毛利）→ Company，`WIN`（允许 `0.00`）
  - **Profit Sharing**（可选）→ 各分账账户，`WIN`；无 shares 则跳过
- 比例算法：
  - `FULL_MONTH` / `FIRST_MONTH`：比例 = 1（全额）
  - `PARTIAL_FIRST_MONTH` / `DAY_END_TAIL`：比例 = 闭区间天数 / 该自然月总天数  
    例：`7/15–7/31` → 17/31；尾段 `9/1–9/9` → 9/30
- Buy / Sell / Profit / PS（若有）共用同一比例；金额截断到 2 位小数（不四舍五入）。
- 写入顺序：先 `bank_process_accounting_posted`（`outcome=POSTED`）→ 再写 N 条 `transactions`（共用 `bank_process_posted_id`）。
- `transaction_date` = 该行 `postedDate`；审批一律 `APPROVED`。
- Description（每行金额 = 该行实际进账金额；银行名 = Bank Name）：
  - `FULL_MONTH` / `FIRST_MONTH`：`FULL MONTH (MAY 2026) @MONTHLY 200 | RHB`
  - `PARTIAL_FIRST_MONTH` / `DAY_END_TAIL`：`PRORATED(15/7 - 31/7 | 17 DAYS)@MONTHLY 3200 | RHB`（`d/M` 闭区间 + 天数）
  - 1st 进账固定写 `@MONTHLY`
- 已 `POSTED` / `SKIPPED` 的账期拒绝重复入账。

### Contract 1+1 / 1+2 / 1+3（赔款，已实现）

- 仅 Contract 值为 `1+1` / `1+2` / `1+3`（租期按 **1 个月**；前端 Day end 也只按 1 个月算）。
- **正常出账 / 进账仍按各 frequency 原规则**（1st 仍可有 Partial First Month 等）；`ACTIVE` 时金额不因 +N 放大。
- **非 ACTIVE**（`INACTIVE` / `OFFICIAL` / `E_INVOICE` / `BLOCK` 等）时走赔款：
  - 倍数：1+1 → ×1；1+2 → ×2；1+3 → ×3（Buy / Sell / Profit / 可选 PS）。
  - Description：`COMPENSATION ONE|TWO|THREE MONTH {amt} | {bank}`。
- **情况 A**：Due 已生成、尚未 Post，status 已非 ACTIVE → **同一行** Post 即赔款（frequency 比例仍算，再 × 倍数）。成功后同时将 `COMPENSATION`@`dayStart` 记为 `POSTED`，避免刷新后又冒出 Case B 同行。
- **情况 B**：已有至少一笔正常 `POSTED` 后，status 才非 ACTIVE → Inbox **额外**一笔 `COMPENSATION`，`postedDate = billingStart = billingEnd = dayStart`；Post 时金额 × 倍数，`transaction_date = today`。
- `INACTIVE` / `BLOCK` 且尚无任何正常 POSTED：仍生成正常 frequency Due（便于情况 A 首笔赔款）；一旦有正常 POSTED 后不再出正常 Due，只出 `COMPENSATION`。
- 不做：延长 `day_end`、因赔款改 status。

## Monthly

- 需要 `dayStart`、`dayEnd`。
- 以 `dayStart` 为首个 posted 锚点，后续月份使用月度锚点（`dayStart` 日 − 1，见 `monthlyAnchor`）。
- `billingStart = postedDate`，`billingEnd = postedDate + 1 个月`。
- 最后一期 posted 锚点为 `dayEnd`；`periodType = MONTHLY`。
- 返回 **所有** `postedDate <= today`、**不早于创建月** 的锚点账期（可多笔并列）。
- **非当月跳过**：与 1st 相同（创建月之前的锚点不出）。
- **未提交保留 / Delete / Refresh** 与 1st of Every Month 相同。

### Post to Transaction（Monthly，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `MONTHLY` frequency + `periodType = MONTHLY`。
- 金额：**每期全额**（比例恒为 1）；无 partial、无 day end tail、无按天数折算。
- 金额来源与写入规则同 1st 进账：Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`。
- Description：`MONTHLY BILL 400 | MARI`（金额 = 该行金额；银行名 = Bank Name）。

## Once

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`。
- 用于一次性合同付款。
- `postedDate = billingStart = billingEnd = dayStart`，`periodType = ONCE_ONE_OFF`。
- `today >= dayStart` 时生成；未到 dayStart 则等待。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月）：
  - `dayStart` 早于创建月 → 不进 Due，并自动改为 `INACTIVE`。
  - 例：7 月创建、`dayStart` 在 6 月 → 直接跳过。
- **已生成未执行保留**：一旦进入 Due，跨到新自然月后仍保留，直到用户提交或手动 Skip（不再用 today 所在月过滤）。
- 每个 process 最多返回一笔账单。
- Once 在 `POSTED` 或用户手动 `SKIPPED` 后自动改为 `INACTIVE`（停止再出 Due）。

### Post to Transaction（Once，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `ONCE` frequency + `periodType = ONCE_ONE_OFF`。
- 金额：**全额**（比例恒为 1）；无 partial / 按天数折算。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（= `dayStart`）。
- Description：`ONCE (20/07/2026) @ 1000 | PBB`（`dd/MM/yyyy` + 本行金额 + Bank Name）。
- 入账成功后将该 process `status` 改为 `INACTIVE`。

## Week

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`。
- `dayStart` 是永久周账期锚点；用户手动改为 `INACTIVE` 时立即停止出账。
- 每周一期为互不重叠的 7 天（含首尾）：`billingStart` 至 `billingStart + 6 天`。
- 下一期从上一期结束日的次日开始：`nextStart = billingEnd + 1 天`。
- `postedDate = billingStart`，`periodType = WEEKLY`。
- 例：`dayStart = 2026-06-25`：
  - `06-25 – 07-01`，posted `06-25`
  - `07-02 – 07-08`，posted `07-02`
  - `07-09 – 07-15`，posted `07-09`
  - `07-16 – 07-22`，posted `07-16`
  - `07-23 – 07-29`，posted `07-23`
- 只有 `postedDate <= today` 的账期可以出现，未来账期等待。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月，不是 today 所在月）：
  - 仅当 `dayStart` 早于创建月时生效。
  - 完全落在创建月之前的周账期不出。
  - 跨入创建月的周账期仍出（如 7 月创建、`6/25–7/1`）。
- **已生成未执行保留**：创建月及之后已到期、未 POSTED/SKIPPED 的周账期，跨到新自然月后仍留在 Due，直到用户提交或手动 Skip（不会因换月被过滤掉）。
- 一个 Week process 可以同时返回多笔已到期、未结算的账单。

> 周账期为互不重叠的闭区间 `[billingStart, billingEnd]`（共 7 天），下一期从 `billingEnd + 1` 开始。前端 Billing Date 显示完整的 `start – end` 区间。

### Post to Transaction（Week，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `WEEK` frequency + `periodType = WEEKLY`。
- 金额：**每期全额**（比例恒为 1）；无 partial / 按天数折算。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（= `billingStart`）。
- Description：`WEEK (01/06/2026 - 07/06/2026) @ 100 | PBB`（`dd/MM/yyyy` 闭区间 + 本行金额 + Bank Name）。

## Day

- 只需要 `dayStart`，不需要 `dayEnd` 和 `contract`（与 Week 对齐）。
- `dayStart` 为每日账单锚点；用户手动改为 `INACTIVE` 时立即停止出账。
- 每一天一笔账单：`postedDate = billingStart = billingEnd = 该日`，`periodType = DAILY`。
- 生成范围：从 `max(dayStart, 合同创建月 1 日)` 到 `today`（含）。
- **非当月跳过**（对照月 = 合同 `createdAt` 所在自然月）：
  - 创建月之前的日期一律不出（Day 无跨月踏入例外）。
- **已生成未执行保留**：已到期未结算的日账单跨月后仍留在 Due，直到用户提交或手动 Skip。
- 当月内尚未到期的未来日等待，不提前列出。
- 例：`dayStart = 2026-07-01`，今天 `2026-07-17` → Due 并列 `07-01` … `07-17`；进 8 月若未提交则这些行继续存在。
- 例：7 月创建、`dayStart = 2026-06-15` → 只从 7 月 1 日起出；6 月日不出。
- 一个 Day process 可以同时返回多笔已到期、未结算的账单。

### Post to Transaction（Day，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`。
- 仅支持 `DAY` frequency + `periodType = DAILY`。
- 金额：**每期全额**（比例恒为 1）。
- Buy / Sell / Profit 必拿，Profit Sharing 可选；ledger → N 条 `transactions`。
- `transaction_date` = 该行 `postedDate`（单日）。
- Description：`DAY (10/06/2026) @ 22 | PBB`（`dd/MM/yyyy` + 本行金额 + Bank Name）。

## 单笔与多笔返回

| Frequency | 一个 process 在 Accounting Due 中的返回数量 |
| --- | --- |
| 1st of Every Month | 可同时多笔（所有已到期未结算月） |
| Monthly | 可同时多笔（所有已到期未结算锚点） |
| Once | 最多 1 笔 |
| Week | 可同时多笔 |
| Day | 可同时多笔 |

## 前后端约定

- 后端 Frequency：`FIRST_OF_EVERY_MONTH`、`MONTHLY`、`ONCE`、`WEEK`、`DAY`。
- 前端值：`1st_of_every_month`、`monthly`、`once`、`week`、`day`。
- Once / Week / Day 表单禁用并不提交 `dayEnd`、`contract`。
- 前端 Accounting Due 行键必须包含 process、period type 和 posted date，确保多账期（含 1st / Monthly / Week / Day）可独立选择、Skip 与 Refresh 恢复。
- Day（`DAILY`）Billing Date 展示单日；Week（`WEEKLY`）展示 `start – end` 区间。
- Resend（`RESEND_CONSOLIDATED`）：Week / Monthly / 1st 补单 Billing Date 同样展示 `start – end`；Once / Day 补单展示单日。

## Resend（补单）

Resend 在正常 Accounting Due **之外**追加一笔 make-up 账单。不修改合同 `dayStart` / `dayEnd` / `frequency`，不删除正常 ledger，不影响正常出账。

实现隔离：`BankProcessResendService` / `BankProcessResendController` / `BankProcessResendDao`（与 CRUD、Accounting Due 分离）。

### 通用（Phase 1）

- API：`POST /api/bank-process/resend`，body 使用 `AccountingDueDTO`：`tenantId`、`bankProcessId`、`dayStart`、`dayEnd`、`frequency`。
- 仅 `ACTIVE` 可 Resend。
- 成功后写入 `bank_process.resend_schedule_*`（每个 process 最多一笔开放补单）。
- Inbox 追加：`periodType = RESEND_CONSOLIDATED`。
- **开放补单一律展示**：不因 `today` / `asOf`、未到期、创建月门槛等过滤；过去或未来 `dayStart` 只要 Resend 成功都会出现在 Accounting Due。
- **同 process + 同 `dayStart`** 且补单仍在 Due（未 Post/Skip）→ 拒绝。
- **同 process + 不同 `dayStart`** → 允许，**覆盖**旧开放补单（只保留最新）。
- **不同 process** → 互不影响。
- Skip 该 `RESEND_CONSOLIDATED` 时清除 `resend_schedule_*`；再次同锚点 Resend 会清除此前 `SKIPPED` make-up ledger，**允许同 dayStart + 同 frequency 再补一次**。
- **尚未实现**：Post 同日锁、Maintenance 清锁。

### Post to Transaction（Resend，已实现）

- API：同 `POST /api/bank-process/accounting-due/post`；识别 `periodType = RESEND_CONSOLIDATED`。
- 账期窗口 = Due 上用户补单的 `billingStart` / `billingEnd` / `postedDate`（不按合同滚动锚点重算）。
- Frequency 优先用开放补单的 `resend_schedule_frequency`，否则用 process frequency。
- 金额来源与写入顺序同正常 Post：Buy / Sell / Profit 必拿，PS 可选；ledger `POSTED` → N 条 `transactions`；`transaction_date = postedDate`。
- Post 成功后清除 `resend_schedule_*`（与 Skip 相同）。
- **不做赔款**；**Once 补单 Post / Skip 都不改** process status（补的是正常账单，不是惩罚金）。
- 已 `POSTED` 的同键拒绝重复进账；Skip 后再 Resend 同锚点见上文。

| Frequency | 金额 | Description |
|-----------|------|-------------|
| Monthly | 全额 | `MONTHLY BILL {amt} \| {bank}` |
| Week | 全额 | `WEEK (start - end) @ {amt} \| {bank}`（用户那一周） |
| Day | 全额 | `DAY (单日) @ {amt} \| {bank}` |
| Once | 全额 | `ONCE (单日) @ {amt} \| {bank}`；不 → INACTIVE |
| 1st of every month | 按月切段加总（见下） | **一律** `PRORATED(d/M - d/M \| N DAYS)@MONTHLY {amt} \| {bank}`（整段起止；N = 闭区间总天数；即使全是整月也不写 FULL MONTH） |

#### 1st Resend 金额（按月切段加总，仍一笔 Due / 一笔 Post）

- 将用户 `[dayStart, dayEnd]` 按自然月切开；每段贡献 = 该段闭区间天数 / 该月总天数（整月 = 1）。
- 各月贡献加总为比例，再 × Buy / Sell / Profit / PS。
- 例：`6/10 – 7/31` → `(21/30) + 1`；例：`6/1 – 8/31` → `1 + 1 + 1`。
- Due / ledger / transactions 仍只有一笔 `RESEND_CONSOLIDATED`。

### 1st of Every Month（已实现）

- **必须**填 `dayStart` 与 `dayEnd`（缺一不可），且 `dayEnd >= dayStart`。
- 补单窗口为整段 `[dayStart, dayEnd]`（可跨月），**不按自然月拆多笔**。

### Monthly（已实现）

- **只填** `dayStart`；`dayEnd` 禁用、不提交。
- 补单窗口自动为 `[dayStart, dayStart + 1 month]`（与正常 Monthly 一期一致）。
- 例：`dayStart = 6/20` → 补单 `6/20 – 7/20`，一笔 `RESEND_CONSOLIDATED`。
- 同样不按创建日 / 未到期过滤，Resend 成功即进 Accounting Due。

### Once（已实现）

- Resend 产品规则与 Monthly 一致（只填 `dayStart`、冲突/覆盖/一律展示、`RESEND_CONSOLIDATED`）。
- 窗口用 **Once 自己的单日逻辑**（不是 Monthly 的 +1 month）：`postedDate = billingStart = billingEnd = dayStart`。
- 例：`dayStart = 6/20` → 补单单日 `6/20`。
- 不套用正常 Once 的「未到 dayStart 不出」「早于创建月跳过」；Resend 成功即进 Due。

### Week（已实现）

- Resend 产品规则与 Monthly / Once 一致（只填 `dayStart`、冲突/覆盖/一律展示、只补一次）。
- 窗口用 **Week 自己的一周逻辑**：`[dayStart, dayStart + 6]`（含首尾 7 天），`postedDate = dayStart`。
- 例：`dayStart = 6/25` → 补单 `6/25 – 7/1`，一笔 `RESEND_CONSOLIDATED`。
- Accounting Due **Billing Date** 与正常 Week 一致，展示 `start – end`（from – to）。
- **不是**按正常 Week 从锚点滚到 today 出多周；只出用户本次选的这一周。

### Day（已实现）

- Resend 产品规则与 Monthly / Once 一致（只填 `dayStart`、冲突/覆盖/一律展示、只补一次）。
- 窗口用 **Day 自己的单日逻辑**：`postedDate = billingStart = billingEnd = dayStart`。
- 例：`dayStart = 6/25` → 补单单日 `6/25`。
- **不是**按正常 Day 从锚点滚到 today 出多日；只出用户本次选的那一天。
