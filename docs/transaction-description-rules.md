# Transaction Description Storage

`transactions.description` 入库约定。修改提交写入或 History 展示拼装时，必须同步更新本文档。

## 原则

| 用途 | 行为 |
|------|------|
| **数据库入库** | 提交时写入可读审计文案（账户用 **name**，空则回退 account code） |
| **History UI** | **不受入库文案影响**：读接口仍按账户视角重算展示（账户用 **code**），与改前一致 |
| Domain / Bank Process | 系统已有 description（如 `PAY DOMAIN FEE`、Due 文案）保持原样，不套用本规则 |

## 入库格式

### RATE 转账腿（leg1 / leg2）

```text
EXCH RATE {rate} {ccy1} {amount} > {ccy2} | FROM {fromAccountName} TO {toAccountName}
```

例：`EXCH RATE 3 MYR 1010 > SGD | FROM Alice TO Bob`

- `{rate}`：优先 `rateExpression`，否则 exchange rate plain
- `{amount}`：leg1 金额（高精度 plain，与 `TransactionMoneyFormat.formatMoney` 一致）
- 每条腿用**该腿**自己的 From / To 账户名

### RATE Middle-Man

与 History 收款方 MARKUP 展示同形，账户用 **name**：

```text
MARKUP X {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}     # fee
MARKUP {middlemanRate} {ccy1} {amount} > {ccy2} | FROM {leg1ToAccountName}  # rate multiplier
```

例：`MARKUP X MYR 1010 > SGD | FROM Alice`

旧数据可能仍为 `RATE_MIDDLEMAN_FEE` / `RATE_MIDDLEMAN_RATE`；查询需兼容两种。

### CONTRA / PAYMENT / CLAIM / CLEAR / PROFIT

```text
{TYPE} FROM {fromAccountName} TO {toAccountName}
```

例：`CONTRA FROM Alice TO Bob`

### ADJUSTMENT

不变：

```text
ADJUSTMENT - WIN/LOSS
```

## History 展示（不改业务观感）

读 History 时：

- RATE 转账腿：仍按视角拼 `EXCH RATE … | FROM {code}` **或** `… | TO {code}`
- Middle-Man：仍拼 `MARKUP … | FROM {leg1ToCode}`
- CONTRA 等：仍按视角拼 `{TYPE} FROM {code}` **或** `{TYPE} TO {code}`
- 若 DB 已是审计串 `{TYPE} FROM … TO …` / `EXCH RATE … | FROM … TO …`，History **覆盖为**上述视角文案后再返回
- Domain 等其它 description（不匹配审计串）原样保留

## 实现入口

| 层 | 文件 |
|----|------|
| 提交写入 | `TransactionSubmitServiceImpl`（`formatTransferDescription` / EXCH / MARKUP helpers） |
| History 重写 | `TransactionHistoryServiceImpl`（`applyRateHistoryPresentation` / `applyManualTransferHistoryPresentation`） |
| Middle-Man 识别 | `TransactionMapper.xml`（`rateMiddlemanFeeDescription` 兼容 `MARKUP X %`） |

## 与金额精度

金额在 description 中的写法遵循 [transaction-amount-precision.md](./transaction-amount-precision.md) 的 plain 真值序列化（不强制 round-to-2）。
