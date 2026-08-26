# Known Issues — Transaction / Data Capture Summary (待優化 #2)

記錄日期：2026-08-21（2026-08-26 更新：原第 1 項〔Data Capture Summary amount = 0.00 交易鏈路〕、
第 2 項〔Payment History Export PDF 異常〕、Games Process List Copy From 補 Spring 端點、以及
Member 頁面登錄報表查詢功能（含 Account Link、mini grid），已全部修復/實現/驗證完畢，歸檔記錄搬到
[`frontend-springboot-migration.md`](frontend-springboot-migration.md) 第 36 節、第 35 節、
第 7.6 節、第 7.7/38 節後，從本文件移除）

## 已歸檔（點連結看完整記錄，本文件不再重複）

- **Data Capture Summary 執行 amount = 0.00 交易的相關問題**（寫入端 1a / Payment History 顯示端 1b /
  Transaction「Show all 0 balance」1c，全部 ✅）：
  [`frontend-springboot-migration.md` 第 36 節](frontend-springboot-migration.md#36-data-capture-summary-amount--000-交易链路修复归档2026-08-25--2026-08-26已解决)
- **Payment History 頁 Export PDF（Win/Lose Report）異常**（幣別選單空白 2a / Export 報錯 2b /
  Id Product 欄位空白 2c，全部 ✅）：
  [`frontend-springboot-migration.md` 第 35 節](frontend-springboot-migration.md#35-payment-history-export-pdf--group-账本币别选单空白修复2026-08-26)
- **Games Process List — Copy From** 補 Spring 端點（新 process 的 currency/remove word/replace word/
  remark/description/day use/formula 全部改成後端權威深拷貝，並修了選項選不中的既有 bug；配套加了
  process/account/currency「有 transaction 數據不允許刪」的刪除防護，全部 ✅）：
  [`frontend-springboot-migration.md` 第 7.6 節](frontend-springboot-migration.md#76已完成2026-08-26games-process-list--copy-from-补-spring-端点)
  ／實現細節見 [`process-copy-from-and-delete-guards.md`](process-copy-from.md)
  （前端見 `Count-frontend/docs/process-copy-from-frontend-changes.md`）
- **Member 頁面**（boot 流程、Account Link 判斷、有 link 時的多帳號 mini grid、Win/Loss 報表）
  補 Spring 端點，全部完成並經真實帳號實測、修了幾個實測才暴露出來的 bug：
  [`frontend-springboot-migration.md` 第 7.7 / 38 節](frontend-springboot-migration.md#77已完成member-页面-boot--account-link--win-loss-报表含-mini-grid-全部迁移-spring2026-08-26)
  ／實現細節見 [`member-account-link-report.md`](member-account-link-report.md)
  （前端見 `Count-frontend/docs/member-winloss-springboot-migration.md`）

---

## 計劃中（尚未實現，詳見 frontend-springboot-migration.md 第 7 節）

- **Dashboard**、**Reset Password** 仍調 PHP，用戶已知、留到之後再做（見
  frontend-springboot-migration.md 第 7 節條目列表）

## 優先級原則

後端功能與前端功能對齊、確認完全沒有遺留問題之後，才考慮單純的前端代碼優化/清理（殘留死代碼、重構
等）。詳見 [`frontend-springboot-migration.md` 第 8 節](frontend-springboot-migration.md#8-维护约定)
第 7 條。

## 额外补充

**后续：** 有时间得优化前端的代码文件，以及残留的死代码等等——但按上面「優先級原則」，要等第 7 節列的
PHP 依赖/功能缺口全部清理完再做。
