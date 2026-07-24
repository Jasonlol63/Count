# Transaction Amount Precision

金额精度约定：**存真值，看 2 位。**  
修改入库 scale、API 金额序列化、或 Transaction / Bank Process / Domain 相关金额行为时，必须同步更新本文档，并保持后端与 `Count-frontend` 对齐。

## 原则

| 环节 | 行为 |
|------|------|
| 入库 / 计算 / API | 高精度真值；**禁止**为存储或响应做 round-to-2 |
| 普通交易金额上限 | 小数点后最多 **6** 位；超过则 **拒绝**（不截断、不四舍五入到上限） |
| RATE 交易金额上限 | 小数点后最多 **8** 位；超过则 **拒绝** |
| 汇率 / Middle-Man rate | 最多 **8** 位；超过则 **拒绝** |
| UI 展示 | 一律 **HALF_UP → 2** 位（仅展示，不回写库） |
| 余额 / 汇总 | **高精度累加**，展示时再 round 2 |

DB 字段多为 `DECIMAL(25, 8)`；业务上限（普通 6 / RATE 8）比列定义更严或持平。

## 交易类型与上限

### 普通交易（≤ 6）

含手动提交与系统进账：

- `PAYMENT` / `CLAIM` / `CLEAR` / `CONTRA` / `PROFIT` / `ADJUSTMENT`
- Bank Process → Accounting Due → `transactions.amount`
- Domain fee charge → `transactions.amount`
- Bank Process 表单上的 Buy / Sell / Profit / Insurance / Profit Sharing 等（写入 process 后进 Due）

### RATE 交易（≤ 8）

- Leg1 / Leg2 `amount`
- Middle-Man fee（第一币输入）及换算后的 fee / rate portion
- `exchangeRate` / `middlemanRate`
- 中间结果：`leg1 × rate`、`fee × exchangeRate`、`gross − middleman` 等

## 后端

### 共享入口

`backend/src/main/java/com/eazycount/util/TransactionMoneyFormat.java`

| 方法 | 用途 |
|------|------|
| `NORMAL_AMOUNT_SCALE` (=6) / `RATE_AMOUNT_SCALE` (=8) | 上限常量 |
| `formatMoney` | API 序列化：**plain 真值**（不再 round 2） |
| `requireNormalAmount` / `requireRateAmount` / `requireMaxScale` | 客户端输入：超限抛业务错，原样保留 |
| `normalizeComputedNormal` / `normalizeComputedRate` | 系统计算结果：不超过上限则原样；**仅当 scale 超过上限**时 HALF_UP 到该上限（仍不做 round-to-2） |
| `add` / `nz` / `strip` | 高精度运算 |

### 主要调用点

- `TransactionSubmitServiceImpl`：提交解析与 RATE 校验（容差 `1e-8`）
- `AccountingDueServiceImpl`：Due 进账金额（`normalizeComputedNormal`）
- `DomainFeeChargeServiceImpl`：Domain 进账金额
- `TransactionHistoryServiceImpl` / `TransactionSearchServiceImpl`：History / Search 返回真值；running balance 高精度累加后再 `formatMoney`

### 示例

| 场景 | 输入 / 计算 | 入库 | API 返回 | UI（前端） |
|------|-------------|------|----------|------------|
| 普通 PAYMENT | `10.123456` | `10.123456` | `10.123456` | `10.12` |
| 普通 PAYMENT | `10.1234567` | 拒绝 | — | — |
| RATE leg | `10.12345678` | `10.12345678` | `10.12345678` | `10.12` |
| RATE leg | `10.123456789` | 拒绝 | — | — |
| Due 折算 `price × ratio` | 结果小数 ≤6 | 原样 | 真值 | round 2 |
| Due 折算 | 结果小数 >6 | HALF_UP 到 6 位后入库 | 真值 | round 2 |

## 前端（Count-frontend）

### 共享入口

`src/utils/money/moneyDecimal.js`（`MoneyDecimal`）

| 方法 | 用途 |
|------|------|
| `UI_SCALE` (=2) | 仅展示 |
| `NORMAL_AMOUNT_SCALE` / `RATE_AMOUNT_SCALE` | 与后端一致 |
| `formatUiFixed` / `formatUiMoney` | 展示 HALF_UP 2（后者带千分位） |
| `toPlainAmount` / `requireNormalAmount` / `requireRateAmount` | 提交真值；超限抛错 |
| `normalizeComputedRate` | 表达式/中间结果：超 8 位才 HALF_UP 到 8 |

Transaction 展示封装：`src/pages/transaction/lib/transactionFormat.js`  
（`formatTransactionGridMoneyHalfUp`、`formatRateAmount` 等均为 **display-only**）。

### 提交 vs 展示

- **提交**：普通走 `requireNormalAmount`；RATE legs / fee / rate 走 `requireRateAmount` / plain，**禁止**再 `formatFixedHalfUp(..., 2)` 后作为 payload。
- **RATE 表单**：state 存真值；只读金额框用 `formatRateAmount`（= UI 2 位）显示。
- **列表 / History / 汇总**：行内与 totals 保持高精度；`TransactionWinLossCell` 等在 render 时 round 2。
- **Bank Process**：表单/API 存 plain ≤6；列表单元格仍 `formatBankMoneyFixed2`（展示 2 位）。

### 后续页面约定

凡金额相关页面：

1. 展示 → `MoneyDecimal.formatUiMoney` / `formatUiFixed`（或 Transaction 已有 display helper）
2. 提交 / 写入 → `requireNormalAmount` 或 `requireRateAmount`
3. 累加 → `MoneyDecimal.add` 等，**不要**先 round 2 再加

不要在业务里直接写 `toFixed(2)` / 对 payload 做 round-to-2。

## 历史数据

改规则前已按 2 位入库的旧数据继续按 2 位真值存在；新数据才可能出现 6/8 位小数。

## Related docs

- [transaction-description-rules.md](./transaction-description-rules.md) — `transactions.description` audit storage vs History UI

## 相关文件（速查）

**Backend**

- `util/TransactionMoneyFormat.java`
- `service/impl/TransactionSubmitServiceImpl.java`
- `service/impl/TransactionHistoryServiceImpl.java`
- `service/impl/TransactionSearchServiceImpl.java`
- `service/impl/AccountingDueServiceImpl.java`
- `service/impl/DomainFeeChargeServiceImpl.java`

**Frontend**

- `utils/money/moneyDecimal.js`
- `pages/transaction/lib/transactionFormat.js`
- `pages/transaction/lib/transactionSubmitHelpers.js`
- `pages/transaction/lib/transactionPaymentLogic.js`
- `pages/transaction/hooks/useTransactionForm.js`
- `pages/bankprocesslist/lib/bankProcessHelpers.js`
- `pages/bankprocesslist/bankProcessListApi.js`
