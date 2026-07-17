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

## Monthly

- 需要 `dayStart`、`dayEnd`。
- 以 `dayStart` 为首个 posted 锚点，后续月份使用月度锚点（`dayStart` 日 − 1，见 `monthlyAnchor`）。
- `billingStart = postedDate`，`billingEnd = postedDate + 1 个月`。
- 最后一期 posted 锚点为 `dayEnd`；`periodType = MONTHLY`。
- 返回 **所有** `postedDate <= today`、**不早于创建月** 的锚点账期（可多笔并列）。
- **非当月跳过**：与 1st 相同（创建月之前的锚点不出）。
- **未提交保留 / Delete / Refresh** 与 1st of Every Month 相同。

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
- 待交易功能完成：Once 在 `POSTED` 或用户手动 `SKIPPED` 后也应自动改为 `INACTIVE`。

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
