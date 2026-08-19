# Customer Report — Spring API 迁移说明

> **前端仓库**：`../Count-frontend/`
> **后端前缀**：`/api/report/*`
> **最后更新**：2026-08-13

---

## 1. 背景

Customer Report 原本完全跑在旧版 PHP（`count168/api/reports/customer_report_api.php`），直接聚合旧表
`data_capture_details`，**不经过 `transactions`（Payment History）这一层**。

本次迁移把「Customer Report 只统计 Payment History 里 Product = DATA CAPTURE 的记录」这个需求做成新的
Spring 端点：从 `transactions` 表按 Payment History 对 DATA CAPTURE 的同一套判定口径聚合 Win/Lose，
不再依赖已被 `TABLE_MIGRATION.md` 标记淘汰的 `data_capture_details`。

---

## 2. 原则

- **Tenant 模型**：请求体传数字 `tenantId`（`tenant.id`），不使用旧的 `company_id` / `group_id` /
  `report_scope` / `view_group` 等 scope 参数——与 Maintenance / Payment History 已迁移端点一致。
- **DATA CAPTURE 判定口径**：`transaction_type IN ('WIN','LOSE') AND bank_process_posted_id IS NULL`，
  与 `TransactionHistoryMapper.xml` 的 `findDataCaptureHistoryLines` / `aggregateDataCaptureBfByAccount`
  完全一致（Payment History 把这批记录标成 `product = "DATA CAPTURE"` 用的就是这个条件）。
- **不校验 `approval_status`**：Data Capture 走 Summary Submit 落库即生效，从不需要审批，所以聚合时
  不加 `approval_status = 'APPROVED'` 这个条件（这点和 Bank Process 的聚合口径不同）。
- **一个 tenant 一次请求**：Group（AP/IG）+ Company（95/AG/CX/RS/VG）之间的跨租户汇总，和其它已迁移页面
  一样由前端对每个 tenant 循环请求再合并，后端从未支持过跨租户查询。

---

## 3. Spring 端点

| 能力 | 方法 | 路径 | Body |
|------|------|------|------|
| Customer Report 列表 | POST | `/api/report/customer-report/list` | `CustomerReportDTO`（见 §4） |

成功响应：`{ "success": true, "message": "...", "data": [...] }`；失败：`{ "success": false, "message": "..." }`（HTTP 200，与 `MaintenanceController` 同款）。

---

## 4. 请求 / 响应结构：`CustomerReportDTO`

同一个类既是请求体也是响应行（沿用 `MaintenanceTransactionDTO` 的复用风格，未拆 Request/Result 两个类）。

**请求字段**

```json
{
  "tenantId": 42,
  "currencyCodes": ["MYR", "SGD"],
  "dateFrom": "2026-01-01",
  "dateTo": "2026-08-13",
  "accountId": null,
  "showAll": false
}
```

| 字段 | 说明 |
|------|------|
| `tenantId` | 必填，> 0 |
| `currencyCodes` | 可选；`null` / 空数组 = 该账号名下 **所有** 已分配币种（`account_currency`），对应 UI 的「Show All Currencies」 |
| `dateFrom` / `dateTo` | 必填，`yyyy-MM-dd` 或 `d/M/uuuu`（`TransactionDateParse`） |
| `accountId` | 可选；`null`/`0` = All Accounts |
| `showAll` | Show All 开关（见 §6） |

**响应行**（`data` 数组，最后一笔是合成的 Total 行）

```json
[
  { "accountRowId": 101, "accountCode": "1SLOT", "accountName": "1SLOT", "currencyCode": "MYR", "winAmount": 7.71, "loseAmount": -0.76 },
  { "totalRow": true, "winAmount": 4960107.92, "loseAmount": -4960107.95 }
]
```

- 正常行：`totalRow` 为空/`false`。
- 最后一行：`totalRow: true`，只有 `winAmount`/`loseAmount`，代表本次请求范围内所有行的加总（跨币种直接相加，
  和旧版 `total_win`/`total_lose` 一样是「混合」值——只有单币种查询时这个数字才有实际意义，多币种由前端按
  币种分组各自小计，见 §7）。

---

## 5. 查询设计：为什么要经过 `account_currency`

最初的实现只接受单一 `currencyId`，对每个 tenant 账号做固定的 `INNER JOIN currency`。核对旧版
`customer_report_api.php` 的 `buildReportData()` / `getAccountCurrenciesBulk()` 后发现：**账号本身有一份
「已分配币种」清单**（新 schema 里是 `account_currency` 表），报表应该按账号自己的已分配币种展开，而不是无脑
对所有账号套用同一个 currency。已改为：

```sql
FROM account a
INNER JOIN account_tenant_access ata ON ata.account_id = a.id AND ata.tenant_id = #{tenantId}
INNER JOIN account_currency ac ON ac.account_id = a.id AND ac.tenant_id = #{tenantId}
INNER JOIN currency cur ON cur.id = ac.currency_id AND cur.tenant_id = #{tenantId}
LEFT JOIN transactions t
    ON t.account_id = a.id AND t.tenant_id = #{tenantId} AND t.currency_id = ac.currency_id
   AND t.bank_process_posted_id IS NULL AND t.transaction_type IN ('WIN','LOSE')
   AND t.transaction_date BETWEEN #{dateFrom} AND #{dateTo}
```

即：`account_tenant_access` 决定「该 tenant 下有哪些账号」，`account_currency` 决定「这个账号要展示哪些
币种」，`currencyCodes` 只是在这份已分配币种清单上再做一次可选过滤。没有交易记录的 (账号, 币种) 组合仍会
出现在结果里，win/lose 都是 0，交给 Service 层的 Show All 逻辑决定要不要隐藏。

**已知限制**：旧版对「账号完全没有分配任何币种」这种边缘情况有一条 fallback（`currency=null`，把该账号所有
币种的 win/lose 混在一起显示）。新版没有实现这条 fallback——如果账号在 `account_currency` 里一条记录都没
有，它不会出现在 Customer Report 里。新 schema 下账号创建流程已经要求指定币种，预期这个边缘情况不会出现；
如果之后发现有账号确实缺 `account_currency` 记录，需要另外评估要不要补这条 fallback。

---

## 6. Service 层规则（`ReportServiceImpl`）

- Win 取正值；Lose 从 Dao 拿到的正值取负号（对齐旧版 `lose_total`「本来就是负数」的显示习惯）。
- **Show All 关闭**：只保留 Win 或 Lose 任一非 0 的 (账号, 币种) 行；**开启**：全部保留，包含 0/0。
- Total 行：对 Dao 返回的**全部**行（过滤前）分别加总 Win / Lose，再作为一笔 `totalRow=true` 的记录追加到
  列表末尾——因为被 Show All 过滤掉的行本来就是 0/0，所以「过滤前加总」和「过滤后加总」结果相同，不需要
  分开算两次。
- 不校验 `approval_status`（见 §2）。

---

## 7. 前端整合（`customerReportApi.js`）

只改了这一个文件——`CustomerReportPage.jsx` / `CustomerReportFilters.jsx` / `CustomerReportTable.jsx`
完全没动，靠 `fetchCustomerReport()` 在新旧接口之间做适配，返回值形状维持跟旧版 PHP 完全一样：

```js
{ success: true, data: [...], total_win, total_lose, date_from, date_to }
```

- `fetchCustomerReportOnce()`：单一 tenant 的 Spring 请求，把响应里 `totalRow: true` 的那一行拆出来当
  `totalWin`/`totalLose`，剩下的行映射成 Table 认得的 `{ id, account_id, name, currency, win, lose }`。
- `fetchCustomerReport()`：解析 `reportScope`（跟 Transaction Maintenance 共用同一个
  `resolveCustomerReportScope`）——`mode === "aggregate"` 时对 `mergeCompanyIds` 逐个 tenant 请求，用
  `reportAmountAdd`（高精度小数加法）把多个 tenant 的 `data` 拼接、`total_win`/`total_lose` 相加；否则
  只对 `scopeCompanyId` 单一 tenant 请求一次。
- **币种不用再前端循环**：新后端一次请求就能按 `currencyCodes` 返回多币种的行（`CustomerReportTable.jsx`
  本来就会在前端按 `currency` 字段分组、各自算小计），所以「Show All Currencies」/多选币种直接把
  `selectedCurrencies` 转成 `currencyCodes` 传给后端，不需要像 tenant 那样逐个循环。

### 7.1 打开页面直接被踢回 Dashboard（`company_has_gambling` 字段名过期）

**现象**：点进 Customer Report，页面瞬间跳回 `/dashboard`，Network 看到一串来自 `useDashboardPage.js`
的 `get_scope_account_currencies_api.php` / `get_company_currencies_api.php` /
`user_currency_order_api.php` 500——这些 500 只是**结果**，不是原因，它们是 Dashboard 页自己的 boot
逻辑，跟 Customer Report 无关，只因为被错误重定向到 `/dashboard` 才被触发。

**根因**：`CustomerReportPage.jsx` 的 boot effect 检查 `!u.company_has_gambling` 才决定要不要跳转，
但 Spring `/auth/current-user`（`SessionUser.java`）从来没有 `company_has_gambling` 这个字段，只有
`tenant_has_game`——所以这个判断永远是 `true`，**不管用户有没有 `report` 权限都会被踢回 Dashboard**。

**修复**：改用 `utils/auth/sessionTenant.js` 里已有的 `sessionHasTenantGame(me)`（内部做了
`me?.tenant_has_game ?? me?.company_has_gambling` 的兼容判断，`bankProcessLogic`/`sessionTenant.js`
其它页面已经在用同一个 helper）。

```diff
- if (!canReport || !u.company_has_gambling) {
+ if (!canReport || !sessionHasTenantGame(u)) {
```

**同款 bug 还在 `DomainReportPage.jsx:186`**，本次没有一并修（超出 Customer Report 范围），之后要点进
Domain Report 大概率会踩到一样的重定向。

### 7.2 Account / Currency 下拉也对齐到 Spring

`fetchAccounts()` 和 `fetchReportScopeCurrencies()` 原本打的旧 PHP 端点（`get_accounts_api.php`、
`get_scope_account_currencies_api.php`）在反向代理把所有 `/api/*` 转发给 Spring 后必然 500——跟
`docs/maintenance-navigation.md` §11.6.2 的 `fetchAccounts` 500 是同一类问题。这两个是 Customer Report
自己真正会用到的下拉数据源（不是 Dashboard 那三个无关的），本次一并对齐：

| 功能 | 旧 PHP | 新 Spring | 说明 |
|------|--------|-----------|------|
| Account 下拉 | `api/transactions/get_accounts_api.php` | `POST /api/account/list?tenant_id=` | 复用 `accountListApi.js` 的 `fetchAccountListByTenantId`（跟 Formula Maintenance 账户下拉同一个函数，见 `maintenance-navigation.md` §11.6.2）。聚合模式逐 tenant 请求，按 `id` 去重合并，`account_id` 排序。**不过滤 status**——对齐旧版 Customer Report 账户列表本来就没有 ACTIVE/INACTIVE 过滤。 |
| Currency 下拉 | `api/transactions/get_scope_account_currencies_api.php` | `POST /api/currency/list?tenant_id=` | `reportCompanyApi.js` 里改写，逐 tenant 请求后按 `code` 去重合并排序。后端目前只支持单 tenant，没有 group/scope 聚合参数（`view_group`/`group_aggregate`/`subsidiary_accounts_only` 全部丢弃）。 |

**没有动、也不需要动的**（跟 Customer Report 无关，Dashboard 自己的技术债）：

| 旧 PHP | 调用方 | 备注 |
|--------|--------|------|
| `get_company_currencies_api.php` | `reportCompanyApi.js` 的 `fetchCurrencies()` | 全仓库零调用点，死代码，没碰 |
| `user_currency_order_api.php` | `useDashboardPage.js` | 纯 Dashboard 页逻辑，Spring 端完全没有对应实现（连币种排序这个概念现在都是前端 localStorage），不在本次范围 |

---

## 8. 本地验证清单

1. 打开 Customer Report 页，Network 应看到 `POST /api/report/customer-report/list`，body 含数字
   `tenantId`、`currencyCodes` 数组（或 `null`）。
2. 单币种（非 Show All Currencies）：确认列表 + 底部 Total 数字和旧版一致。
3. 勾选 Show All Currencies 或多选币种：确认按币种分段、每段小计正确（前端 `reportAdd` 计算）。
4. 切到 Group 模式并选多个 Company 聚合：Network 应看到对应数量的 `customer-report/list` 请求（每个
   tenant 一次），列表数据是多个 tenant 结果拼接后的。
5. 开关 Show All（账号显示开关）：确认 0/0 的账号行按预期显示/隐藏。
6. 选中 `Account` 下拉里的某个账号：确认只返回该账号的行。
7. 有 `report` 权限的用户点进 Customer Report **不应该**被弹回 `/dashboard`。
8. Account / Currency 下拉都能正常列出选项，Network 分别看到 `POST /api/account/list` 和
   `POST /api/currency/list`，不再出现 `get_accounts_api.php` / `get_scope_account_currencies_api.php`。

---

## 9. 变更文件清单（2026-08-13）

**后端**

| 文件 | 说明 |
|------|------|
| `dao/ReportDao.java` | `findCustomerReportRows` |
| `dto/CustomerReportDTO.java` | 请求 + 响应行合一 |
| `service/ReportService.java` / `service/impl/ReportServiceImpl.java` | Show All / Lose 取负 / Total 合成 |
| `controller/ReportController.java` | `POST /api/report/customer-report/list` |
| `resources/mybatis/ReportMapper.xml` | `account` × `account_currency` LEFT JOIN `transactions` |

**前端**

| 文件 | 说明 |
|------|------|
| `pages/report/customer/customerReportApi.js` | `fetchCustomerReport` 改走 Spring；`fetchAccounts` 改走 `POST /api/account/list`（见 §7.2） |
| `pages/report/shared/reportCompanyApi.js` | `fetchReportScopeCurrencies` 改走 `POST /api/currency/list`（见 §7.2） |
| `pages/report/customer/CustomerReportPage.jsx` | 修复 `company_has_gambling` 过期字段名导致的 Dashboard 误跳转（见 §7.1） |

---

## 10. 维护约定

- 新增字段时：先改 `CustomerReportDTO` + 本文，再改 `normalizeSpringCustomerReportRow`。
- 币种口径、tenant 循环写法与 [`process-list-spring-api.md`](./process-list-spring-api.md) §5、
  `maintenance-navigation.md` 保持一致；DATA CAPTURE 判定口径与
  [`transaction-datacapture-winloss.md`](./transaction-datacapture-winloss.md) 保持一致——两边任一处改动
  判定条件，另一处要同步检查。

---

## 11. 2026-08-18 补充：找回被覆盖的迁移 + 清掉 Bank-only 检测的最后一个 PHP 调用

`customerReportApi.js` / `reportCompanyApi.js` 在本次迁移（2026-08-13，`6d7801b`）之后，被同一天晚些时候
的大批量提交 `4f00f14`（"new version frontend...already change account/admin/transaction page to
springboot api"，316 个文件的整仓快照式覆盖）意外整体回退回了纯 PHP 版本——`git log` 上看得到 `6d7801b`
之后紧跟 `4f00f14` 把这两个文件的内容整段替换回旧的 `customer_report_api.php` / `get_accounts_api.php`
写法。`CustomerReportPage.jsx` 本身没有回退（`4f00f14` 之后又被继续加了 Group/Company pill、
currency 跨页同步、snapshot cache 等新功能），所以页面代码一直是按 Spring 版本的 `fetchCustomerReport` /
`fetchAccounts` 参数和返回值形状在调用——只是背后的实现被换回了会 500 的 PHP 端点。

本次把 `customerReportApi.js` / `reportCompanyApi.js` 按 `6d7801b` 的实现重新对齐（`fetchReportScopeCurrencies`
改用 `utils/api/currencyApi.js` 的 `fetchCurrencyListByTenantId`，与 Account/Currency 设置页共用同一个
helper，而不是各自手写一份 fetch）；`reportCompanyApi.js` 里死代码 `fetchCurrencies`（`get_company_currencies_api.php`，
零调用点）一并删除。

顺带修掉一个页面自己的 PHP 依赖：`CustomerReportPage.jsx` 的 `checkBankOnly`（切换 Company 后判断是不是
Bank-only、要不要跳去 Bank Process List）原本调用 `reportCompanyApi.js` 的 `fetchCompanyPermissions`，打的
是 `api/domain/domain_api.php`——同样会被反向代理 500，导致 catch 静默吞掉，Bank-only 检测实际上从没生效过。
改成跟 `utils/auth/sidebarPermissions.js` 的 `canShowReportInSidebar` 同一套判定：`companyMatchesBankOnlyPillScope`
(`utils/company/companyCategoryFlags.js`)，纯前端根据已加载的 `companies` 行 / session flags 缓存判断，
不用再发请求。`fetchCompanyPermissions` / `isBankOnlyCategoryCompany` 在 `reportCompanyApi.js` 里也一并删除。

**教训**：以后做大范围「整仓快照替换」式的提交前，先确认要不要 `git diff` 一下当天更早的迁移提交，避免
覆盖掉刚做完的工作。

### 11.1 前端改动文件清单（2026-08-18）

| 文件 | 改动 |
|------|------|
| `pages/report/customer/customerReportApi.js` | `fetchCustomerReport` / `fetchAccounts` 从纯 PHP 实现重新改回 Spring：`POST /api/report/customer-report/list`（tenant 循环聚合、拆分 `totalRow` 行）+ `fetchAccountListByTenantId`（`POST /api/account/list`）。不再触碰 `customer_report_api.php` / `get_accounts_api.php`。 |
| `pages/report/shared/reportCompanyApi.js` | `fetchReportScopeCurrencies` 改用 `utils/api/currencyApi.js` 的 `fetchCurrencyListByTenantId`（`POST /api/currency/list`），tenant 循环 + 按 code 去重合并。删除死代码 `fetchCurrencies`（`get_company_currencies_api.php`，零调用点）、删除 `fetchCompanyPermissions` / `isBankOnlyCategoryCompany`（原打 `api/domain/domain_api.php`，见下）。 |
| `pages/report/customer/CustomerReportPage.jsx` | `checkBankOnly` 不再调用 `reportCompanyApi.js` 的 `fetchCompanyPermissions`（PHP，一直在静默 500，判定从未生效），改成纯前端 `companyMatchesBankOnlyPillScope`（`utils/company/companyCategoryFlags.js`），基于已加载的 `companies` 行 / session flags 缓存判断，无需额外请求。 |

同一次事故也影响了 Domain Report，改动清单见
[`domain-report-spring-migration.md` §10.1](./domain-report-spring-migration.md#101-前端改动文件清单2026-08-18)。
