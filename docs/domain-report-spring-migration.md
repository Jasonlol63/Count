# Domain Report — Spring API 迁移说明

> **前端仓库**：`../Count-frontend/`
> **后端前缀**：`/api/report/*`
> **最后更新**：2026-08-13

---

## 1. 背景

Domain Report 原本完全跑在旧版 PHP（`count168/api/reports/domain_report_api.php`），以 `process` 为主表
（无数据的 process 也显示 0），直接聚合旧表 `data_capture_details.processed_amount`——跟遷移前的
`customer_report_api.php` 是同一種寫法。

跟 [`customer-report-spring-migration.md`](./customer-report-spring-migration.md) 一样，本次把
Win/Lose 的資料來源换成 `transactions` 表（同一套 DATA CAPTURE 判定口径），不再依赖已淘汰的
`data_capture_details`。

---

## 2. 跟 Customer Report 的關鍵差異

| | Customer Report | Domain Report |
|---|---|---|
| 分組維度 | Account | **Process**（GAME 類別，透過 `data_captures.process_id` 接上） |
| Lose 顯示 | 取負 | **維持正值，不取負** |
| 第四欄 | 無 | **Win/Lose = Win − Lose**（餘額核對值，理論上接近 0） |
| Turnover | 無 | **Turnover = Win + Lose** |
| Show All | 有開關 | **沒有**，process 永遠全部顯示（含 0/0） |
| Currency 維度 | 有（`account_currency` 多幣種） | **沒有** |
| Category 過濾 | 不適用 | **只看 `process.category = 'GAME'`**（Bank Process 完全不算在內，這點是新 schema 才有的規則，legacy `process` 表根本沒有 category 欄位） |

---

## 3. Spring 端點

| 能力 | 方法 | 路徑 | Body |
|------|------|------|------|
| Domain Report 列表 | POST | `/api/report/domain-report/list` | `DomainReportDTO`（見 §4） |

成功響應：`{ "success": true, "message": "...", "data": [...] }`（跟 `ReportController` 其他端點同款）。

---

## 4. 請求 / 響應結構：`DomainReportDTO`

同一個類既是請求體也是響應行（跟 `CustomerReportDTO` 同一種複用風格）。

**請求字段**

```json
{ "tenantId": 42, "dateFrom": "2026-01-01", "dateTo": "2026-08-13", "processId": null }
```

| 字段 | 說明 |
|------|------|
| `tenantId` | 必填，> 0 |
| `dateFrom` / `dateTo` | 必填 |
| `processId` | 可選；`null`/`0` = All Process |

**響應行**（`data` 數組，最後一筆是合成的 Total 行）

```json
[
  { "processRowId": 7, "processCode": "25C01", "description": "28WIN", "turnoverAmount": 33189.00, "winAmount": 16594.49, "loseAmount": 16594.51, "winLoseAmount": -0.02 },
  { "totalRow": true, "turnoverAmount": 1234567.89, "winAmount": 617283.94, "loseAmount": 617283.95, "winLoseAmount": -0.01 }
]
```

---

## 5. 查詢設計

`process` 當主表（driving table，`p.category = 'GAME'`），逐層 LEFT JOIN 出去：

```sql
FROM process p
LEFT JOIN data_captures dc
    ON dc.process_id = p.id AND dc.tenant_id = p.tenant_id
   AND dc.capture_date BETWEEN #{dateFrom} AND #{dateTo}
LEFT JOIN data_capture_line dl ON dl.capture_id = dc.id AND dl.tenant_id = p.tenant_id
LEFT JOIN transactions t
    ON t.id = dl.transaction_id AND t.tenant_id = p.tenant_id
   AND t.bank_process_posted_id IS NULL AND t.transaction_type IN ('WIN','LOSE')
WHERE p.tenant_id = #{tenantId} AND p.category = 'GAME'
```

- **Description 組合**：新 schema 的 `process` 已經沒有 `description_id` 純量欄位了（legacy 是 1:1），
  變成 `process_description_link` 多對多。用相關子查詢
  `GROUP_CONCAT(DISTINCT pd.name ORDER BY pd.name SEPARATOR ', ')` 組出來。實務上如果每個 process 都只
  掛一個 description，這個子查詢的結果就跟 legacy 的 `"code (description)"` label 一模一樣；如果之後
  發現有 process 掛了多個 description，畫面上會看到用逗號串起來的多個名稱，這是預期行為，不是 bug。
- **Service 層計算** `turnoverAmount = win + lose`、`winLoseAmount = win − lose`（Lose 不取負，直接相加
  相減即可）。
- 沒有 Show All 過濾——所有 GAME process 一律列出，包含 0/0。
- Total 行：對 Dao 返回的**全部**行分別加總後 append 到 list 最後，`totalRow: true`。

---

## 6. 前端整合（`domainReportApi.js`）

只改了這一個文件 + `DomainReportPage.jsx` 的一行 bug 修復——`DomainReportFilters.jsx` /
`DomainReportTable.jsx` 完全沒動，回傳值形狀維持跟舊版 PHP 完全一樣：

```js
{ success: true, data: [{process, description, turnover, win, lose, win_lose}], totals: {turnover, win, lose, win_lose}, date_from, date_to }
```

### 6.1 一個重要的範圍界定：Group-only（AP/IG payroll）沒有遷移

Domain Report 的 Process 篩選框在「純 Group 模式」（`selectedGroup` 有值、但沒選 Company）下，UI 邏輯
（`domainReportUsesSalaryBonusProcesses`，`scope.mode === "group"`）會切換成只顯示固定的
**SALARY / COMMISSION / BONUS** 三個 payroll process。這幾個 process 是 **BANK 類別**，不是 GAME——
跟 Customer/Domain Report 設計時談好的「只看 GAME process win/lose」是完全不同的資料領域（比較像是薪資/
佣金這類 Bank Capture 流程,不是 Win/Lose 報表本體）。

新的 Spring Domain Report 端點目前**只查 `category = 'GAME'`**，不會回傳 SALARY/COMMISSION/BONUS
這幾個 BANK process 的資料，所以：

- `fetchDomainReport()` / `fetchProcesses()` 在 `reportScope.mode === "group"` 時，**維持呼叫原本的舊
  PHP**（`fetchDomainReportLegacy` / `fetchProcessesLegacy`，程式碼原封不動搬過去），不做任何遷移。
- 只有 **Company 模式** 跟 **Group 內選了 Company（aggregate）模式** 這兩種——也就是畫面上實際看 GAME
  process win/lose 報表的主要場景——才走新的 Spring 端點。

**已知限制**：純 Group 模式（不選 Company）本來就會撞到 `get_scope_account_currencies_api.php` 那一類
「反向代理把 `/api/*` 轉發給 Spring、legacy PHP 路由 500」的問題（跟 Customer Report §7.2 提過的是同一
個技術債），這個限制不是本次遷移造成的，也不在這次範圍內處理。如果之後要做 SALARY/COMMISSION/BONUS 的
Domain Report，需要另外設計（BANK category 的資料來源、Bank Capture 的 win/lose 定義都跟 GAME 不一樣）。

### 6.2 Process 下拉（Company / Aggregate 模式）

複用 Games Process List 已經在用的 `POST /api/process/process-list`（`processListApi.js` 的
`fetchProcessListByTenantId`，已經會過濾掉 BANK 類別）。聚合模式逐 tenant 請求、按 `id` 去重合併，
`process` 排序。回傳的每個選項是 `{id, process, description, display_text}`，`display_text` 組法跟
legacy 一致：`description` 有值時是 `"CODE (DESCRIPTION)"`，否則就是 `CODE`。

### 6.3 `company_has_gambling` 過期字段名 bug

跟 [`customer-report-spring-migration.md` §7.1](./customer-report-spring-migration.md) 提到的是同一個
bug，`DomainReportPage.jsx:186` 一路留到現在才修。改用 `sessionHasTenantGame(u)`
（`utils/auth/sessionTenant.js`）。

---

## 7. 本地驗證清單

1. Company 模式打開 Domain Report，Network 應看到 `POST /api/report/domain-report/list`，body 含數字
   `tenantId`。
2. 確認 Turnover/Win/Lose/Win-Lose 四欄數字，Win-Lose 應該接近 0；Total 行數字正確。
3. Process 下拉能列出當前 tenant 全部 GAME process，選中特定 process 後只返回該筆。
4. 切到 Group 模式並選多個 Company 聚合：Network 應看到對應數量的 `domain-report/list` 請求（每個
   tenant 一次）。
5. 切到純 Group（不選 Company）模式：Process 下拉應該只顯示 SALARY/COMMISSION/BONUS，Network 走的是
   舊版 `domain_report_api.php`（不是 Spring 端點）——這是預期行為，不是遺漏。
6. 有 `report` 權限的用戶點進 Domain Report **不應該**被彈回 `/dashboard`。

---

## 8. 變更文件清單（2026-08-13）

**後端**

| 文件 | 說明 |
|------|------|
| `dto/DomainReportDTO.java` | 請求 + 響應行合一 |
| `dao/ReportDao.java` | `findDomainReportRows` |
| `resources/mybatis/ReportMapper.xml` | `process`（`category='GAME'`）LEFT JOIN `data_captures` → `data_capture_line` → `transactions` |
| `service/ReportService.java` / `service/impl/ReportServiceImpl.java` | Turnover/Win-Lose 計算，無 Show All |
| `controller/ReportController.java` | `POST /api/report/domain-report/list` |

**前端**

| 文件 | 說明 |
|------|------|
| `pages/report/domain/domainReportApi.js` | `fetchDomainReport`/`fetchProcesses` 改走 Spring（Company/Aggregate 模式），Group-only 模式維持舊版 PHP（見 §6.1） |
| `pages/report/domain/DomainReportPage.jsx` | 修復 `company_has_gambling` 過期字段名（見 §6.3） |

---

## 9. 維護約定

- 新增字段時：先改 `DomainReportDTO` + 本文，再改 `normalizeSpringDomainReportRow`。
- DATA CAPTURE 判定口徑、`transactions` join 方式與
  [`customer-report-spring-migration.md`](./customer-report-spring-migration.md) 保持一致，任一處改動
  判定條件，另一處要同步檢查。
- 如果之後要幫 Group-only（SALARY/COMMISSION/BONUS）補上 Spring 端點，需要另外評估 BANK category 的
  win/lose 資料來源，不能直接套用現有 `category = 'GAME'` 的 query。

---

## 10. 2026-08-18 補充：找回被覆蓋的遷移

跟 [`customer-report-spring-migration.md` §11](./customer-report-spring-migration.md#11-2026-08-18-补充找回被覆盖的迁移--清掉-bank-only-检测的最后一个-php-调用)
是同一次事故：`domainReportApi.js` 在遷移完成（`6d7801b`）當天稍晚被整倉快照式提交 `4f00f14` 整段覆蓋回純
PHP 版本，Company/Aggregate 模式的 `fetchDomainReport` / `fetchProcesses` 一路在打會 500 的
`domain_report_api.php`。`captureMaintenanceLogic.js` 的 Group-only payroll process 下拉也依賴這個檔案的
`fetchProcesses` 導出，所以連帶受影響。

本次按 `6d7801b` 的實現重新對齊 `domainReportApi.js`（Company/Aggregate 走 Spring
`api/report/domain-report/list` + `api/process/process-list`，Group-only 維持舊版 PHP，見 §6.1，行為未變）；
同時把 `DomainReportPage.jsx` 的 `checkBankOnly` 從打 `api/domain/domain_api.php` 的
`fetchCompanyPermissions` 改成前端純判定的 `companyMatchesBankOnlyPillScope`
(`utils/company/companyCategoryFlags.js`)，理由同 Customer Report §11。

### 10.1 前端改動文件清單（2026-08-18）

| 文件 | 改動 |
|------|------|
| `pages/report/domain/domainReportApi.js` | `fetchDomainReport` / `fetchProcesses` 從純 PHP 實現重新改回 Spring：Company/Aggregate 走 `POST /api/report/domain-report/list`（tenant 循環聚合、拆分 `totalRow` 行）+ `fetchProcessListByTenantId`（`POST /api/process/process-list`）；Group-only（SALARY/COMMISSION/BONUS）維持 `fetchDomainReportLegacy` / `fetchProcessesLegacy` 打舊版 `domain_report_api.php`，行為與 §6.1 一致、未變動。此檔案同時被 `captureMaintenanceLogic.js` 的 Group-only payroll process 下拉引用，一併修復。 |
| `pages/report/domain/DomainReportPage.jsx` | `checkBankOnly` 不再調用 `api/domain/domain_api.php`（PHP，一直在靜默 500，判定從未生效），改成純前端 `companyMatchesBankOnlyPillScope`（`utils/company/companyCategoryFlags.js`）。 |

Customer Report 那邊的 `reportCompanyApi.js` / `CustomerReportPage.jsx` 改動清單見
[`customer-report-spring-migration.md` §11.1](./customer-report-spring-migration.md#111-前端改动文件清单2026-08-18)。
