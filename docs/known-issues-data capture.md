# Pending Items — Spring Boot Migration & Optimizations (待優化 #2)

記錄日期：2026-08-21（2026-08-26 更新：原第 1 項〔Data Capture Summary amount = 0.00 交易鏈路〕、
第 2 項〔Payment History Export PDF 異常〕、Games Process List Copy From 補 Spring 端點、以及
Member 頁面登錄報表查詢功能（含 Account Link、mini grid），已全部修復/實現/驗證完畢，歸檔記錄搬到
[`frontend-springboot-migration.md`](frontend-springboot-migration.md) 第 36 節、第 35 節、
第 7.6 節、第 7.7/38 節後，從本文件移除。同日再更新：把「Dashboard」「Reset Password」尚未遷移
Spring 的條目補上具體現況說明，並新增一條計劃中項目——Account 頁面批量新增
bidirectional/unidirectional 帳號連結）

## 計劃中（尚未實現，詳見 frontend-springboot-migration.md 第 7 節）

### 1. Dashboard 遷移 Spring Boot API 格式

`useDashboardPage.js` / `dashboardRoutePrefetch.js` 仍調 `dashboard_bootstrap_api.php`、
`update_company_session_api.php`；倉庫和文檔目前都找不到對應的 Spring 端點規劃，需要先確定
`dashboard_bootstrap_api.php` 的 Spring 契約（回傳格式對齊 Spring DTO 慣例），再遷移頁面本身與
`warmDashboardRouteCache()` 背景預熱邏輯。用戶已知、留到之後再做（見 frontend-springboot-migration.md
第 7 節）。

---

### 2. Reset Password 遷移 Spring Boot API 格式

**2026-08-27 更新：admin/user 這一半已實現並接線完畢**（owner 那一半留待之後）：

- 後端新增 `AuthController#sendResetTac` / `#resetPassword`（`POST /auth/send-reset-tac` /
  `POST /auth/reset-password`），`AuthServiceImpl` 內以 `scope="admin"` 呼叫，只查 `user` 表
  （`AuthDao.findAdminByEmail` / `updateAdminPassword`，SQL 見 `LoginMapper.xml`）
- TAC 改用 Redis（`PasswordResetTacStore`，仿 `AuthTokenStore` 寫法），不用
  `password_reset_tac` / `password_reset_tac_owner` 兩張表：`SET EX 15min` 存驗證碼、
  `SET NX EX 60` 當重發冷卻鎖、失敗 5 次即讓該碼失效、驗證成功立刻刪除（一次性）
- Email 發送走 `spring-boot-starter-mail` + `JavaMailSender`（新增 `PasswordResetMailService`），
  `application.yml` 新增 `spring.mail.*`（本地預設指向 `localhost:1025`，需要本地跑
  MailHog/smtp4dev 才能實際收信；SMTP 失敗只記 log，不拋給前端，避免洩漏帳號是否存在）
- `send-reset-tac` 不論帳號存不存在都回同一句成功話術，只有冷卻鎖觸發時才回不同訊息
- 前端 `resetPassword.js` 已改呼叫 `authApi.sendResetTacRequest()` /
  `authApi.resetPasswordRequest()`，`ResetPasswordPage.jsx` 欄位從 `companyId` 改名
  `tenantCode`，並用 `localizeAuthApiMessage()` 翻譯後端訊息
- owner 那一套（`password_reset_tac_owner`／owner 登入頁的重置密碼）尚未實現，之後要做時可以把
  `AuthServiceImpl` 的 `sendResetTac`/`resetPassword` 抽成帶 `scope` 參數的共用實作
  （`RESET_SCOPE_ADMIN` 已經是一個獨立常數，之後加 `RESET_SCOPE_OWNER` 走 `Owner` 查詢即可）

**後端 TAC 發送/驗證這塊本次順帶討論了以下優化方案，已在上面的實作中採納：**

- **TAC 存儲改用 Redis，不用 DB**：`schema.sql` 已存在的 `password_reset_tac` /
  `password_reset_tac_owner` 兩張表（`PRIMARY KEY (email, tenant_id)`）設計本身沒問題，但 TAC 是
  短生命週期、一次性、不需要長期留存的數據，比較適合用專案裡已經接好的 Redis（`StringRedisTemplate`
  ／參考現成寫法 [`AuthTokenStore.java`](../backend/src/main/java/com/eazycount/security/AuthTokenStore.java)），
  用 `SET key value EX ttl` 讓 Redis 自動過期，不用像 DB 方案那樣手動比對 `expires_at` 欄位、也不用
  額外寫清理過期行的 job。實作後這兩張表可以直接不用（要不要 `DROP` 待之後決定，先保留死 schema 不影響功能）
- **60 秒重發冷卻，且必須在後端強制**：發送 TAC 後 60 秒內同一個 email/tenant 不能再次觸發發送，
  避免使用者手滑連點兩次收到兩封信、也避免被拿來對 SMTP 服務商洗量。可以跟 TAC 本體共用 Redis 機制，
  用 `SET NX EX 60` 當冷卻鎖（key 存在＝還在冷卻，直接拒絕並回傳剩餘秒數）；驗證碼本身另外用
  10–15 分鐘的 TTL（兩個時間窗不同，互不影響）。前端按鈕 disable 60 秒只是體驗優化，不能作為唯一防線
- **驗證碼一次性使用**：`reset-password` 呼叫成功後立刻刪掉對應 Redis key，防止同一個碼被重放
- **失敗次數限制**：驗證碼校驗錯誤達到一定次數（例如 5 次）就讓該碼直接失效，防止暴力枚舉 6 位數字
- **不要回顯「這個 email 是否存在」**：不管 email 在不在系統裡，`send-reset-tac` 都回統一話術
  （「如果該信箱存在，驗證碼已寄出」），避免被用來枚舉系統內有哪些帳號 email
- **admin／owner 兩套 TAC 邏輯重複**：`password_reset_tac` 與 `password_reset_tac_owner`
  幾乎是同一套邏輯拆成兩張表，Service 層可以考慮做成帶 `scope` 參數的共用實作，避免以後改一處忘改
  另一處（呼應最近一次「拆分 process 業務邏輯」提交的整理方向）
- **Email 發送本身走 `JavaMailSender` + SMTP**：本地開發即可測試，不需要先部署上線才生效（本地機器
  連外網打 SMTP server 即可），開發階段也可以用 MailHog/smtp4dev 這類本地假 SMTP 抓包工具避免真的發信

---

### 3. Account 頁面 Account Link 優化（批量新增 bidirectional / unidirectional）

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
