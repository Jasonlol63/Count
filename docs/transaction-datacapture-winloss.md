# Transaction Payment / Payment History — Data Capture Win/Loss 补聚合

> **相关**：[datacapture-spring-api.md](./datacapture-spring-api.md) §2.8（Summary 最终 Submit 写 `transactions`）、[transaction-list-payment-winloss-filters.md](./transaction-list-payment-winloss-filters.md)（Show Win/Loss Only 等筛选）
> **最后更新**：2026-08-11

---

## 1. 问题

Data Capture Summary Submit（GAME）成功后，`data_captures` / `data_capture_line` / `process_submitted` / `transactions` 四张表都正确写入了数据（见 `datacapture-spring-api.md` §2.8），但 Transaction Payment 主列表、右上 Payment History 明细都看不到这几笔——跟公司/日期/币别筛选无关，换任何条件都查不到。

**根因：** `TransactionMapper.xml` 里所有跟 WIN/LOSE 相关的聚合查询（`aggregateBankProcessWinLoss`、`aggregateBankProcessBfByAccount`、`findBankProcessHistoryLines`）都写死 `t.bank_process_posted_id IS NOT NULL`——这个前提假设"WIN/LOSE 只会来自 Bank Process 记账流程"（`AccountingDueServiceImpl.insertTxnLine()` 才会 set 这个字段）。但 `DataCaptureSummaryServiceImpl.toTransaction()`（Data Capture Summary Submit）也会写 `transaction_type IN ('WIN','LOSE')`，却从来不 set `bankProcessPostedId`，插入后这一列是 `NULL`——落进了任何查询分支都覆盖不到的空档：manual 那几条（ADJUSTMENT/PROFIT/RATE middleman）虽然也是 `bank_process_posted_id IS NULL`，但过滤的 `transaction_type` 不是 WIN/LOSE，接不住。

排查时确认过整个后端只有两处会写 `transaction_type IN ('WIN','LOSE')`：

| 来源 | `bank_process_posted_id` |
|------|---------------------------|
| `AccountingDueServiceImpl.insertTxnLine()`（Bank Process 记账 posting 流程） | 总是非空 |
| `DataCaptureSummaryServiceImpl.toTransaction()`（Data Capture Summary Submit） | 总是 `NULL` |

所以按 `bank_process_posted_id IS NULL AND transaction_type IN ('WIN','LOSE')` 切分，精确对应 Data Capture 来源，不会漏、也不会跟其他来源混。

---

## 2. 修复：新增对称聚合查询

不改现有查询的 `IS NOT NULL` 条件（避免影响 Bank Process 记账原有行为），而是照抄一份、把条件反过来，各自独立成一条 DAO/mapper 方法，最后在 service 层合并。

### 2.1 Transaction Payment 主列表（`TransactionSearchServiceImpl`）

| 新增 | 镜像自 | 差异 |
|------|--------|------|
| `TransactionDao.aggregateDataCaptureWinLoss` | `aggregateBankProcessWinLoss` | 仅 `bank_process_posted_id IS NOT NULL` → `IS NULL`，其余 SQL（bf/period 分段求和、WIN 正 LOSE 负、currency/role 过滤）完全一致 |

`TransactionSearchServiceImpl.buildWinLossSearchSlice()`（原 `buildBankProcessSearchSlice`，改名见 §3）在原有 bank/adjustment/profit/rate-middleman 四路之外，多查一路 `aggregateDataCaptureWinLoss`，用同一套 `mergeWinLossAggregateRows`（按 `accountDbId + currencyCode` 合并求和）拼进结果。

### 2.2 Payment History 明细（`TransactionHistoryServiceImpl`）

| 新增 | 镜像自 | 差异 |
|------|--------|------|
| `TransactionDao.aggregateDataCaptureBfByAccount` | `aggregateBankProcessBfByAccount` | 同上，只反转 NULL 判断 |
| `TransactionDao.findDataCaptureHistoryLines` | `findBankProcessHistoryLines` | 反转 NULL 判断；**去掉** `bank_process_accounting_posted`/`bank_process` join（Data Capture 这批数据没经过 Bank Process 记账，硬 join 查不到东西），`cardOwner` 直接给 `NULL`；`bankProcessLine` 仍给 `TRUE`（见 §4） |

`TransactionHistoryServiceImpl.buildWinLossHistorySlice()`（原 `buildBankProcessHistorySlice`）把这两个新查询的结果并进 BF map 和明细行列表。

---

## 3. 顺带做的命名重构（不改逻辑，纯改名）

用户反馈 `buildBankProcessSearchSlice` 这个名字容易误导——方法名看起来只处理 Bank Process，实际上里面还揉了 ADJUSTMENT / PROFIT / RATE middleman，现在又加了 Data Capture。两个 service 文件里同样的命名问题一并理顺：

| 文件 | 原名 | 新名 |
|------|------|------|
| `TransactionSearchServiceImpl` | `buildBankProcessSearchSlice` | `buildWinLossSearchSlice` |
| | 局部变量 `bank` | `winLoss` |
| | `applyBankAggregates` | `applyWinLossAggregates` |
| | `MergedAccount.fromBank` | `MergedAccount.fromWinLoss` |
| | `SearchSlice` record 字段 `bankProcess` | `isWinLossSource` |
| `TransactionHistoryServiceImpl` | `buildBankProcessHistorySlice` | `buildWinLossHistorySlice` |
| | 局部变量/参数 `bank` | `winLoss` |

两个类的 Javadoc 头注释也同步改成"Win/Loss（Bank Process + Data Capture + 手动 Adjustment/Profit/Rate-middleman）与 Domain Payment (Cr/Dr) 分别构建"。

**没动的**：`HistoryLineRow.bankProcessLine` 字段名，以及它经 `toHistoryRow()` 映射出去的 `HistoryRow.isBankProcessTransaction` ——后者是暴露给前端的 API 字段，改名涉及前端契约，这次不动。它现在的真实含义已经变成"按 Win/Loss 处理（摆 Win/Loss 列而不是 Cr/Dr 列）"，不再字面等于"来自 Bank Process"；`findDataCaptureHistoryLines` 也把它设成 `TRUE` 以获得同样的显示分支。

---

## 4. Payment History "ID PRODUCT" 列显示 "DATA CAPTURE"

`bankProcessLine=TRUE` 现在真正 Bank Process 记账行和 Data Capture 行都会用到（为了让金额落在 Win/Loss 列），单靠它已经分不出来源。所以新增一个专门字段区分：

| 新增 | 说明 |
|------|------|
| `TransactionDTO.HistoryLineRow.dataCaptureLine` | 仅 `findDataCaptureHistoryLines` 的 SELECT 里给 `TRUE AS dataCaptureLine`；`findBankProcessHistoryLines` 不选这列，MyBatis 留空即为 `null`/false |

`TransactionHistoryServiceImpl.toHistoryRow()` 的 product 分支新增判断（插在 RATE 和「非 Bank 时走 Domain product」之间）：

```java
} else if (isDataCapture) {
    row.setProduct("DATA CAPTURE");
} else if (!isBank) {
    row.setProduct(resolveDomainHistoryProduct(line));
}
```

真正 Bank Process 记账行（`isBank=true` 且 `isDataCapture=false`）继续保持 product 空白，不受影响。

### 4.1 前端也要改（这里当初判断错了）

原以为前端不用改——`transactionHistoryNormalize.js` 确实已经在读 `raw.product` 并透传成 `product` 字段。但渲染 ID PRODUCT 列的两处都有一层前端自己的取值逻辑，只要 `is_bank_process_transaction`（= 后端 `bankProcessLine`）为 `true` 就优先显示 `card_owner`，根本不看 `product`：

- `TransactionHistoryTable.jsx`（Payment History 表格本体）
- `paymentHistoryMemberReportExport.js`（Payment History 报表导出）

因为 §3 提到 `bankProcessLine`/`isBankProcessTransaction` 现在真正 Bank Process 行和 Data Capture 行都是 `true`，而 Data Capture 行没有 `card_owner`（`NULL`），这两处逻辑会把它 fallback 成 `"-"`，`product = "DATA CAPTURE"` 完全被无视——这就是当时排查到的现象（Win/Loss 金额和余额都对，唯独 ID PRODUCT 空白）。

修复方式：两处都改成优先用 `product`（真正 Bank Process 记账行从不 set 这个字段，所以不受影响），没有才 fallback 到 `card_owner`：

```js
const idProductDisplay = r.product || (r.is_bank_process_transaction ? r.card_owner : "") || "-";
```

**结论**：以后再新增一个会复用 `bankProcessLine=TRUE`（走 Win/Loss 显示分支）的来源，除了本文 §4 的后端 `product` 字段，还必须检查前端有没有类似"按 is_bank_process_transaction 二选一"的取值逻辑——不能假设前端只读 `product` 就完事。

---

## 5. 自测

1. Games process Submit（`datacapture-spring-api.md` §2.8 自测 3）→ 打开 Transaction Payment，该账户所在币别行的 Win/Loss 金额包含这笔（不再是 0/缺失）。
2. 打开该账户 Payment History → 出现对应行，`WIN/LOSE` 列有正确签名金额，`DESCRIPTION` 是 `"{processCode}: {formula}"`（如 `BONUS: 3000`），**`ID PRODUCT` 列显示 `DATA CAPTURE`**（不是空白）。
3. Bank Process 记账（`AccountingDueServiceImpl` 走的那条）产生的 WIN/LOSE 行，Payment History 里 `ID PRODUCT` 仍保持空白——确认没被 Data Capture 分支误伤。
4. `Show Win/Loss Only` 勾选后该账户仍会出现（判定逻辑本身没变，只是数据源多了一路，见 `transaction-list-payment-winloss-filters.md`）。

---

## 6. 维护约定

- 以后再新增一个会写 `transactions` 且 `transaction_type IN ('WIN','LOSE')` 的来源（不经过 Bank Process posting），必须同时检查 `TransactionMapper.xml` 三条 WIN/LOSE 查询（`aggregateBankProcessWinLoss`/`aggregateBankProcessBfByAccount`/`findBankProcessHistoryLines`）能不能覆盖到；覆盖不到就照本文 §2 的模式再镜像一份，不要直接改现有 Bank Process 查询的 `IS NOT NULL` 条件。
- 新来源如果需要在 Payment History `ID PRODUCT` 列有专属标签，参照 §4 加一个独立的 `xxxLine` 布尔字段，不要复用 `bankProcessLine`（它已经是"走 Win/Loss 显示分支"的通用开关，不代表来源）。
- 后端给了 `product` 字段不代表前端会自动显示——参照 §4.1，务必确认前端渲染 ID PRODUCT 的地方不是只按 `is_bank_process_transaction` 二选一（`card_owner` vs `product`），而是 `product` 优先。
