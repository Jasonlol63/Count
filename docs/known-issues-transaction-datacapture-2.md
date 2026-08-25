# Known Issues — Transaction / Data Capture Summary (待優化 #2)

記錄日期：2026-08-21（2026-08-25 更新：第 1 項寫入端 + Payment History 顯示端已修復，「Show all 0
balance」子項未測試待驗證；移除已修復項目、新增 Payment History Export PDF 幣別問題）

## 1. Data Capture Summary 執行 amount = 0.00 交易的相關問題

**原始現象：**
在 Data Capture Summary 頁面對某個帳號（account）執行「執行帳單 / 結算」後，如果最終 amount 結果為 0，
之後到 Transaction 頁面查看該筆資料時，查不到對應的 history 記錄。

### 1a. 寫入端：Summary 執行 amount = 0.00 未寫入 transaction 表 ✅ 已修復（2026-08-25）
根因是後端在 amount = 0 時沒有把交易寫入 transaction 表。已修復。

### 1b. Payment History 頁：amount = 0.00 但有 Data Capture 數據的紀錄不顯示 ✅ 已修復（2026-08-25）
Payment History 列表對 amount = 0.00、但實際有對應 Data Capture 數據的紀錄會被過濾掉不顯示。已修復。

### 1c. Transaction 頁「Show all 0 balance」查無 history 記錄 ⚠️ 未測試，狀態待確認
1a/1b 修復後，「Show all 0 balance」這個查詢入口本身還沒有實際測試過，不確定現在是否已一併正常、還是
仍有獨立問題。

**待辦：**
- [ ] 實際測試 Transaction 頁「Show all 0 balance」，確認 1a/1b 修復後這裡查得到對應的 history 記錄
- [ ] 如果仍查不到，需另外排查「Show all 0 balance」自己的查詢邏輯是否還有獨立的過濾問題

---

## 2. Transaction — Payment History 頁 Export PDF（Win/Lose Report）幣別選單異常

**記錄日期：** 2026-08-25

**現象：**
在 Transaction → Payment History 頁面點擊右上角「PDF」開啟 Win/Lose Report 匯出彈窗後，Currency 欄位
顯示「No currencies available for this account」，選不到任何幣別，Export PDF 按鈕也因此被 disable。

**相關程式碼：**
- [`Count-frontend/src/pages/transaction/components/PaymentHistoryExportPdfModal.jsx`](../../Count-frontend/src/pages/transaction/components/PaymentHistoryExportPdfModal.jsx)
  —— 彈窗開啟時呼叫 `fetchPaymentHistoryExportCurrencies(accountId, companyId, groupId, signal)`，
  結果為空陣列時顯示 `m.exportPdfNoCurrencies`（即「No currencies available for this account」）。
- [`Count-frontend/src/pages/transaction/lib/paymentHistoryMemberReportExport.js:231`](../../Count-frontend/src/pages/transaction/lib/paymentHistoryMemberReportExport.js)
  —— `fetchPaymentHistoryExportCurrencies()`：`accountId`/`companyId` 任一無效直接回傳 `[]`；否則呼叫
  `fetchAvailableCurrencies({ tenantId, accountId })`，優先取 `is_linked` 的幣別，取不到才 fallback 全部。

**待辦（後續才優化，本次僅記錄）：**
- [ ] 確認觸發時機下 `accountId`/`companyId`/`groupId` 實際傳入值是否正確（尤其 Group scope 帳號）
- [ ] 確認 `fetchAvailableCurrencies` 對應的 Spring 端點在這個帳號下是否真的沒有回傳任何幣別，還是前端解析/過濾邏輯有誤
- [ ] 修正後應能在彈窗開啟時正確帶出可選幣別，Export PDF 按鈕恢復可用

---

## 備註
第 1 項的寫入端（1a）與 Payment History 顯示端（1b）已於 2026-08-25 修復，僅剩「Show all 0 balance」
（1c）尚未測試、狀態待確認；第 2 項為 2026-08-25 新發現、待後續處理。舊版第 2、3 項（Edit Formula Modal
Add Account 預設值、Input Method 刷新後遺失）已於 2026-08-24 修復完成，故從本文件移除。

## 额外补充
**主要：** Dashboard, ResetPassword, Copy From-Process, Member页面功能都待优化。

**其次：** 明天需要将TransactionDao, TransactionHistoryDao, TransactionSearchDao的comment进行优化。

**后续：** 有时间得优化前端的代码文件，以及残留的死代码等等。
