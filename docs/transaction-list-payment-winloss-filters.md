# Transaction list filters — Show Payment / Show Win/Loss / Show all 0 balance

勾选筛选时的展示规则。修改筛选时同步更新本文档。

## 共同原则

| 项 | 约定 |
|----|------|
| 日期范围 | Transaction 页当前搜索 **From–To** |
| 有对应流水就展示 | 净额非 0，**或** API period 标志有流水（含正负轧成 0） |
| balance = 0（有流水） | 只要本期有对应列动账 → **仍展示**（算「有流水」类，不是「从未动账」） |
| 从未动账 | 该账户×币种 **没有任何 APPROVED 交易历史** → 仅在勾选 **Show all 0 balance** 时补进列表 |

## Show Payment Only

- 看 **Cr/Dr**：`PAYMENT` / `CLAIM` / `CLEAR` / `CONTRA` / RATE 转账腿、Domain Payment 等
- 判定：`cr_dr` 非 0，或 `hasCrDrInPeriod` → `has_crdr_transactions`

## Show Win/Loss Only

- 看 **Win/Loss**：Bank Process `WIN`/`LOSE`、`ADJUSTMENT`、`PROFIT`、RATE Middle-Man 等
- 判定：`win_loss` / `win_loss_full` 非 0，或 `hasWinLossInPeriod` → `has_win_loss_transactions`
- **不**用 `has_period_id_product_rows`（避免 Payment-only 误入）

## Show all 0 balance

勾选后后端 `showAllZeroBalance=true`，Search 额外返回 **neverTransacted** 壳行（账户×已关联币种、无任何 APPROVED 流水）。

### 单独勾选

展示 = 原有 Search 活动行（含有流水但 balance=0）**∪** 从未动账壳行。

### + Show Win/Loss

展示 = 有 W/L 流水/金额的账户 **∪** 从未动账账户。

### + Show Payment

展示 = 有 Cr/Dr 流水/金额的账户 **∪** 从未动账账户。

### + Show Payment + Show Win/Loss

展示 =（有 W/L **或** 有 Cr/Dr）**∪** 从未动账账户（活动条件为 **OR**）。

## 实现

| 层 | 位置 |
|----|------|
| 后端壳行 | `TransactionDao.findAccountCurrencyShells` + `TransactionSearchServiceImpl` |
| 后端标志 | `SearchRow.neverTransacted` |
| 前端请求 | `buildSpringSearchRequest({ showAllZeroBalance: !hideZeroBalance })` |
| 前端判定 | `rowHasPeriodCrdr` / `rowHasPeriodWinLoss` / `rowIsNeverTransacted` |
| 前端应用 | `filterTransactionTableRows` → `useTransactionSearch.tablePresentation` |
