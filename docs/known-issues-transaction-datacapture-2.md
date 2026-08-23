# Known Issues — Transaction / Data Capture Summary (待優化 #2)

記錄日期：2026-08-21

## 1. Transaction 頁面「Show all 0 balance」查無 history 記錄

**現象：**
在 Data Capture Summary 頁面對某個帳號（account）執行「執行帳單 / 結算」後，如果最終 amount 結果為 0，
之後到 Transaction 頁面點擊「Show all 0 balance」進去查看該筆資料時，查不到對應的 history 記錄。

**推測影響：**
可能是後端在 amount = 0 時沒有寫入對應的 transaction/history 紀錄，或是查詢 history 時把 amount = 0 的紀錄過濾掉了，
導致使用者在前端點進去後看不到任何歷史資料，無法追溯這筆結算的來源。

**待辦：**
- [ ] 確認執行帳單時，amount = 0 是否有正常寫入 transaction history 表
- [ ] 確認 Transaction 頁面「Show all 0 balance」查詢邏輯是否誤過濾了 amount = 0 的紀錄
- [ ] 補上對應的 history 記錄或修正查詢條件

---

## 2. Data Capture Summary — Edit Formula Modal 的 Add Account 功能

**現象：**
在 Edit Formula Modal 中的 Add Account 功能下方，有「選擇公司、選擇集團 (group)」的下拉選單。
在單一 group 模式（single group mode）下，這兩個下拉選單不會自動預設為目前所在的 group，而是顯示未選擇（non selected）狀態。

**預期行為：**
在單一 group 模式下，應該自動預設帶入目前使用者所在的公司／集團，減少使用者手動選擇的步驟。

**待辦：**
- [ ] 找到 Edit Formula Modal 中 Add Account 區塊的公司/集團下拉選單初始化邏輯
- [ ] 在單一 group 模式下，自動預設當前 group（可能與 group mode 判斷邏輯相關，參考 [group-mode-report-sidebar-fix.md](group-mode-report-sidebar-fix.md)）

---

## 3. Data Capture Summary — Input Method（正負號切換等）設定刷新後遺失

**現象：**
在 Data Capture Summary 頁面，對每筆資料設定 input method 後（例如：把正數變負數等功能），
如果重新整理頁面（refresh），這些已設定的 input method 功能會消失，回到預設狀態。

**推測原因：**
input method 的設定可能只存在前端 state（記憶體）中，沒有持久化到後端資料庫或其他儲存機制，
所以刷新頁面後狀態遺失。

**待辦：**
- [ ] 確認 input method 設定目前是否有寫回後端（DB）或只是暫存在前端 state
- [ ] 若尚未持久化，需要設計儲存方式（例如存到對應的 formula/account 設定欄位），讓刷新頁面後可還原
- [ ] 確認頁面載入時是否有正確讀取已儲存的 input method 設定並套用

---

## 備註
以上三項問題目前僅為記錄，尚未深入排查程式碼與根因，後續優化時可依此文件逐項排查與修正。
