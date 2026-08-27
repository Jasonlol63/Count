# Pending Items — Spring Boot Migration & Optimizations (待優化 #2)

記錄日期：2026-08-21（2026-08-26 更新：原第 1 項〔Data Capture Summary amount = 0.00 交易鏈路〕、
第 2 項〔Payment History Export PDF 異常〕、Games Process List Copy From 補 Spring 端點、以及
Member 頁面登錄報表查詢功能（含 Account Link、mini grid），已全部修復/實現/驗證完畢，歸檔記錄搬到
[`frontend-springboot-migration.md`](frontend-springboot-migration.md) 第 36 節、第 35 節、
第 7.6 節、第 7.7/38 節後，從本文件移除。同日再更新：把「Dashboard」「Reset Password」尚未遷移
Spring 的條目補上具體現況說明，並新增一條計劃中項目——Account 頁面批量新增
bidirectional/unidirectional 帳號連結。2026-08-27 更新：原「Reset Password 遷移 Spring Boot API
格式」一項（admin/user 這一半）已實現/接線/驗證完畢，owner 那一半用戶明確表示不需要（非待辦），歸檔
記錄搬到 [`frontend-springboot-migration.md`](frontend-springboot-migration.md#7-尚未迁移-仍走-php)
與獨立文檔 `docs/reset-password-tac-implementation.md`（後端）/
`Count-frontend/docs/reset-password-tac-implementation.md`（前端）後，從本文件移除，原第 3 項
〔Account 頁面 Account Link 優化〕改編號為第 2 項）

## 計劃中（尚未實現，詳見 frontend-springboot-migration.md 第 7 節）

### 1. Dashboard 遷移 Spring Boot API 格式

`useDashboardPage.js` / `dashboardRoutePrefetch.js` 仍調 `dashboard_bootstrap_api.php`、
`update_company_session_api.php`；倉庫和文檔目前都找不到對應的 Spring 端點規劃，需要先確定
`dashboard_bootstrap_api.php` 的 Spring 契約（回傳格式對齊 Spring DTO 慣例），再遷移頁面本身與
`warmDashboardRouteCache()` 背景預熱邏輯。用戶已知、留到之後再做（見 frontend-springboot-migration.md
第 7 節）。

---

### 2. Account 頁面 Account Link 優化（批量新增 bidirectional / unidirectional）

目前 `UserController` 的 `insertAccountLink` 一次只能建立一條 `UserLink`（`bidirectional` 或
`unidirectional` 二選一），Account 頁面若要一次幫多個帳號同時建立 link，只能逐條發請求。目標是加一個
批量端點（一次請求內可以混合多條 bidirectional／unidirectional link），確保「同時新增多個
bidirectional 和 unidirectional 帳號連結」可以在同一次操作內完成，而不是分開多次點擊/多次請求。尚未
實現，待排期。

## 優先級原則

後端功能與前端功能對齊、確認完全沒有遺留問題之後，才考慮單純的前端代碼優化/清理（殘留死代碼、重構
等）。詳見 [`frontend-springboot-migration.md` 第 8 節](frontend-springboot-migration.md#8-维护约定)
第 7 條。

## 额外补充

**后续：** 有时间得优化前端的代码文件，以及残留的死代码等等——但按上面「優先級原則」，要等第 7 節列的
PHP 依赖/功能缺口全部清理完再做。
