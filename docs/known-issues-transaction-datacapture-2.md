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

## 2. Data Capture Summary — Edit Formula Modal 的 Add Account 功能 ✅ 已修復（2026-08-24）

**現象：**
在 Edit Formula Modal 中的 Add Account 功能下方，有「選擇公司、選擇集團 (group)」的下拉選單。
在單一 group 模式（single group mode）下，這兩個下拉選單不會自動預設為目前所在的 group，而是顯示未選擇（non selected）狀態。

**預期行為：**
在單一 group 模式下，應該自動預設帶入目前使用者所在的公司／集團，減少使用者手動選擇的步驟。

**實際根因：**
[`Count-frontend/src/pages/datacapturesummary/hooks/useSummaryAddAccount.js`](../../Count-frontend/src/pages/datacapturesummary/hooks/useSummaryAddAccount.js)
在 group 模式下其實**有**在 `resetToAdd()` 裡預設選取，但預設用的值跟 picker 實際比對用的值型別對不上：

- `groupPickerCompanies` 這一列資料原本是 `{ id: ledgerCtx.tenantId, group_id: ledgerCtx.selectedGroup, ... }`
  ——`id` 是數字 tenant id，`group_id` 才是集團代碼（如 "OK"）。
- 但共用元件 [`AccountModal.jsx`](../../Count-frontend/src/components/AccountModal.jsx) 在
  `groupPickerMode` 下，picker 用來比對「哪一列被選中」的 `picker_value` 優先取 `group_id`（集團代碼），
  不是 `id`（數字 tenant id）。
- 而 `resetToAdd()` 預設 `selectedCompanyIds` 用的是 `[Number(ctx.tenantId)]`（數字）——跟 picker 比對用
  的「集團代碼字串」對不上，於是介面上永遠顯示「未選擇」，即使內部狀態其實已經有值。
- 這個型別不一致同時也會讓「手動點『Choose groups』選集團後直接送出」失敗：手動選取後
  `selectedCompanyIds` 會變成 `["OK"]`（集團代碼字串），原本的送出邏輯
  `selectedCompanyIds.map(Number).filter(...)` 對字串代碼算出 `NaN` 直接被濾掉，導致
  `tenantIds` 變空、跳出「請先選擇公司」錯誤——也就是說在本次修復之前，Summary 頁 Group 模式下的
  Add Account 手動選集團送出本來就會失敗，不只是「不會自動預設」而已。

**修復：**
- `groupPickerCompanies` 改成跟 [`AccountListPage.jsx`](../../Count-frontend/src/pages/account/AccountListPage.jsx)
  自己的 group picker 一致的寫法：`id` 也用集團代碼（`ledgerCtx.selectedGroup`），跟 `group_id`/`company_id`
  同值，讓 picker 的比對值型別一致。
- `resetToAdd()` 預設值改成 `[ctx.selectedGroup]`（代碼字串），現在能跟 picker 的 `picker_value` 正確匹配，
  Modal 一打開就會顯示已選中目前的集團。
- `submitAddAccount()` 送出時，group 模式下改成直接用 `ctx.tenantId`（`resolveSummaryAddAccountContext()`
  已經解析好的真正數字 tenant id）組出 `tenantIds`，不再從 `selectedCompanyIds`（此時是代碼字串）做
  `Number()` 轉換，修正了手動選集團送出失敗的問題。

**驗證狀態：** `npm run build` 通過。**未做瀏覽器實測**——同上，需要在自己的開發環境過一遍：單一 group
模式下點 Edit Formula → Add Account，確認 Group 欄位一打開就已預設帶入目前 group，且能正常送出新增。

---

## 3. Data Capture Summary — Input Method（正負號切換等）設定刷新後遺失 ✅ 已修復（2026-08-24）

**現象：**
在 Data Capture Summary 頁面，對每筆資料設定 input method 後（例如：把正數變負數等功能），
如果重新整理頁面（refresh），這些已設定的 input method 功能會消失，回到預設狀態。

**實際根因（並非未持久化）：**
Input Method 其實**有**存進 localStorage 草稿（同 tab 的 refresh/F5 還原機制），問題出在草稿合併邏輯
`applySavedRefreshRowToModel()`（[`Count-frontend/src/pages/datacapturesummary/lib/summaryRefreshStatePure.js`](../../Count-frontend/src/pages/datacapturesummary/lib/summaryRefreshStatePure.js)）：

- 該函式把 row 分成兩類：「Formula Maintenance 擁有的設定欄位」（account/currency/formula 等，refresh
  時應該讓重新抓到的模板覆蓋草稿，這樣 Formula Maintenance 那邊的改動才能立刻反映到 Summary）跟「純
  session 欄位」（rate 勾選/數值、批次選取，這些永遠信任草稿）。
- **`inputMethod` / `enableInputMethod` 被誤歸進了第一類**，但 Input Method 從來不是 Formula
  Maintenance 模板裡的欄位——它是使用者在 Summary 頁面每一列自己現場選的（`editFormulaFormState.js`）。
- Refresh 時，每一列會先建立一個空白 row model（`enableInputMethod` 預設 `false`），再套用重新抓到的
  模板 `applyMainTemplateToRowModel()`（因為 `mainTemplate.input_method` 永遠是空的，這一步算出的
  `enableInputMethod` 恆為 `false`，但該列會被標記 `templateApplied: true`）。
- 接著合併草稿時，`pickBool()` 邏輯只要 `templateApplied` 為 `true` 就會讓「新鮮值」`false` 直接蓋掉
  草稿裡存的 `true`——`inputMethod` 文字本身因為新鮮值是空字串（falsy）才僥倖 fallback 回草稿保留，
  但控制「是否真的套用這個 input method 效果」的開關 `enableInputMethod` 被強制清成 `false`，公式計算
  時正負號翻轉邏輯因此不生效，回到未設定前的原始金額。

**修復：**
`inputMethod` / `enableInputMethod` 改成跟 `rateChecked`/`rateValue`/`selectChecked` 一樣，永遠信任草稿
（saved）值，不再走「模板優先」邏輯：

```js
inputMethod: saved.inputMethod != null ? saved.inputMethod : row.inputMethod,
enableInputMethod: saved.enableInputMethod != null ? !!saved.enableInputMethod : row.enableInputMethod,
```

**驗證狀態：** `npm run build` 通過。**未做瀏覽器實測**——這是真實登入/session/資料庫的系統，無法在當前
環境安全起一套並行環境驗證，需要在自己的開發環境過一遍：設定 Input Method → 執行頁面內「Refresh page」
按鈕（或瀏覽器 F5）→ 確認金額正負號仍維持設定後的結果。

---

## 備註
第 2、3 項已排查根因並修復（見上），第 1 項目前仍僅為記錄，尚未深入排查程式碼與根因，後續優化時可依此
文件逐項排查與修正。
