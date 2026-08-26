# Known Issues — Transaction / Data Capture Summary (待優化 #2)

記錄日期：2026-08-21（2026-08-26 更新：原第 1 項〔Data Capture Summary amount = 0.00 交易鏈路〕與
第 2 項〔Payment History Export PDF 異常〕已全部修復/驗證完畢，歸檔記錄搬到
[`frontend-springboot-migration.md`](frontend-springboot-migration.md) 第 36 節、第 35 節後，從本文件
移除；新增 Games Process List Copy From、Member 頁面登錄報表查詢功能兩項計劃中工作）

## 已歸檔（點連結看完整記錄，本文件不再重複）

- **Data Capture Summary 執行 amount = 0.00 交易的相關問題**（寫入端 1a / Payment History 顯示端 1b /
  Transaction「Show all 0 balance」1c，全部 ✅）：
  [`frontend-springboot-migration.md` 第 36 節](frontend-springboot-migration.md#36-data-capture-summary-amount--000-交易链路修复归档2026-08-25--2026-08-26已解决)
- **Payment History 頁 Export PDF（Win/Lose Report）異常**（幣別選單空白 2a / Export 報錯 2b /
  Id Product 欄位空白 2c，全部 ✅）：
  [`frontend-springboot-migration.md` 第 35 節](frontend-springboot-migration.md#35-payment-history-export-pdf--group-账本币别选单空白修复2026-08-26)

---

## 計劃中（尚未實現，詳見 frontend-springboot-migration.md 第 7 節）

- **Games Process List — Copy From** 補 Spring 端點：
  [`frontend-springboot-migration.md` 第 7.6 節](frontend-springboot-migration.md#76计划中尚未实现games-process-list--copy-from-补-spring-端点2026-08-26-记录)
- **Member 頁面登錄報表查詢功能** 補 Spring 端點：
  [`frontend-springboot-migration.md` 第 7.7 節](frontend-springboot-migration.md#77计划中尚未实现member-页面登录报表查询功能补-spring-端点2026-08-26-记录)
- **Dashboard**、**Reset Password** 仍調 PHP，用戶已知、留到之後再做（見
  frontend-springboot-migration.md 第 7 節條目列表）

## 優先級原則

後端功能與前端功能對齊、確認完全沒有遺留問題之後，才考慮單純的前端代碼優化/清理（殘留死代碼、重構
等）。詳見 [`frontend-springboot-migration.md` 第 8 節](frontend-springboot-migration.md#8-维护约定)
第 7 條。

## 额外补充

**后续：** 有时间得优化前端的代码文件，以及残留的死代码等等——但按上面「優先級原則」，要等第 7 節列的
PHP 依赖/功能缺口全部清理完再做。
