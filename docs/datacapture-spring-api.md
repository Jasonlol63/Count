# Data Capture — Spring API 对齐说明

> **前端仓库**：`../Count-frontend/`  
> **后端契约**：`DataCaptureGameDTO` + tenant 模型（无 JSON / 无 `scope_*`）  
> **最后更新**：2026-08-07（Summary 最终 Submit 切 Spring，见 §2.7 / §3 / §4）  
> **金额精度**：Summary processed amount 见 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」节（后端 `SummaryAmountFormat` + 前端 `summaryRowAmount.js`，ROUND_DOWN 6/8）

---

## 1. 原则

- **前端对齐 Spring**，不要求后端迁就 PHP 字段（camelCase、`tenantId`、JSON body）。
- **Group / Company pill 的 `id` = `tenant.id`（数字）**；code 仅用于展示与 parent group 筛选。
- 公司列表与切换统一走 **`GET /auth/tenant-accessible`** 与 **`POST /auth/switch-tenant`**。

### 1.1 表分工（tenant 模型）

| 表 | 作用 | 状态 |
|----|------|------|
| `data_captures` | Submit 头（GAME/BANK） | 已有 |
| `data_capture_description` | GAME 选中 description 多选桥 | 已有 |
| `data_capture_formula` | Summary / Maintenance **持久公式配置**（不绑 capture） | 已有 + Formula CRUD Spring |
| `data_capture_line` | Summary **最终 Submit 行快照**（绑 `capture_id`；替代 legacy `data_capture_details`） | **DDL 已写入**；Submit API 仍 PHP / 待 Spring |
| `data_capture_draft*` | BANK 表格草稿 | 已有 |

**明确不建（旧 PHP workaround，Spring 不需要）：**

| 旧表 | 原因 |
|------|------|
| `data_capture_submit_queue` | PHP `post_max_size` / 分批 Submit 用；Spring 可一次事务提交 `data_captures` + `data_capture_line` |
| `data_capture_summary_state` | 存 Summary UI `state_json`；公式已有 `data_capture_formula`，未提交草稿用前端 session/localStorage（BANK 表格另有 `data_capture_draft*`） |

---

## 2. 已迁移 Spring 的 Data Capture 能力

| 能力 | Spring 端点 | 前端入口 |
|------|-------------|----------|
| Group/Company 列表 | `GET /auth/tenant-accessible?all=1` | `fetchOwnerCompaniesAll()` → `tenantAccessibleApi.js` |
| 切换活动 tenant | `POST /auth/switch-tenant?tenant_id=` | `syncCompanySessionApi()` / `syncDataCaptureCompanySession()` |
| Games 按日 process 列表 + 选中回填 | `POST /api/datacapture/games/form` | `postGameCaptureForm()` → `dataCaptureSpringApi.js` |
| 币别 catalog | `POST /api/currency/list?tenant_id=` | `fetchCaptureCurrenciesByTenantId()` |
| Description catalog / CRUD | `POST /api/process/list-description` 等 | `processListApi.js`（经 `dataCaptureSpringApi.js` 封装） |
| 分类 pill（Games/Bank/…） | `/auth/switch-tenant` 返回 `has_game` / `has_bank` | `fetchTenantCategoryPermissions()` |
| Add Formula 保存 | `POST /api/datacapture-summary/formula/save` | `saveAddFormulaSpring()` → `summarySaveTemplatePure.js` |
| Edit Formula / Source 更新 | `POST /api/datacapture-summary/formula/update` | `saveUpdateFormulaSpring()`（按行 `id`；SUB 只改自己） |
| Formula 删除 | `POST /api/datacapture-summary/formula/delete` | `deleteFormulasSpring()`；MAIN 清骨架 / SUB 整行移除；无 subOrder 重排 |
| Add/Edit Formula Account 下拉 | `POST /api/account/list?tenant_id=` | `fetchSummaryFormCatalog()` → `summaryApi.js` |
| 选中 Account 后 Currency | `POST /api/currency/available?tenant_id=&account_id=` | `loadCurrenciesForAccount()` → 仅 linked 写入下拉并默认选中 |

### 2.2 Games Submit → Summary（本阶段：localStorage，不写库）

点 **Submit**：校验 → `saveCaptureSession` → `navigate(/datacapturesummary)`。  
**不调用** `summary_submit_api`；DB 落库在 Summary 最终 Submit 另做。

| 步骤 | 前端文件 |
|------|----------|
| Submit 编排 | `hooks/useDataCaptureSubmitReset.js` |
| 写/读 session | `lib/dataCaptureStorage.js` |
| Summary 加载 | `datacapturesummary/lib/summaryStorage.js` |
| 表头 | `components/SummaryProcessInfo.jsx` |
| 列表行（Id Product） | `table/summaryColumnAData.js` → `buildInitialSummaryRows` |

**`capturedProcessData` 主要字段：** `date`, `tenantId`, `process`（pk）, `processCode`, `processName`, `descriptions[]`, `currency`, `currencyName`, `remark`, `removeWord`, `replaceWordFrom`, `replaceWordTo`, `scopeCompanyId`, `dataCaptureType`。

**`capturedTableData`：** grid 快照；列 A（index 1）→ Summary Id Product 行。

**Scoped key：** `capturedTableData:{tenantId}` + `dc_capture_active_scope_key`。

**列表优先：** 模板 API（仍 PHP）失败时仍展示 Id Product 骨架行。

### 2.3 Bank Submit → Summary + Draft（Phase 1 + 2）

Bank 形态（C168 / bank-only company / group payroll UI，或 `selectedPermission === "Bank"`）：

| 规则 | 说明 |
|------|------|
| Submit 校验 | **Process + Currency + Capture Table**（无 Description） |
| Session | 同 §2.2；额外 `category: "BANK"`、`processCode`（PROFIT/SALARY/COMMISSION/BONUS） |
| process pk | 尽量解析 `process.id`；**失败不挡 Submit** — Summary 用 `processCode` 展示表头，用表格列 A 出列表 |
| **Draft 写** | **仅 Submit 时**；`POST /api/datacapture/bank/draft/save` → `data_capture_draft` + `_cell` |
| **Draft 读** | 选 process+currency 时 `POST /api/datacapture/bank/draft/get` → 回填表格 |
| Draft 粒度 | `UNIQUE (tenant_id, process_id, currency_id)` — 同 process 不同 currency 分存 |
| Draft process | **SALARY / COMMISSION / BONUS** 写入；**PROFIT 永不写入/不回填** |
| Remark | 进 session 表头；**不进 draft** |
| 缺省 BANK process | save 时若无 `process.category=BANK` 行，按 code + currency **自动创建** |

**Draft API body：**

```json
{
  "tenantId": 52,
  "processCode": "SALARY",
  "currencyId": 13,
  "tableData": { "rows": [/* capture snapshot */] }
}
```

或显式 `cells: [{ "rowIndex": 0, "colIndex": 1, "cellValue": "AAA" }]`（`rowIndex` 0-based，`colIndex` 1-based）。

自测：BK + SALARY + MYR + 表格 → Submit → Summary；再回 DC 选 SALARY+MYR → 表格带回 AAA/111；PROFIT Submit 不写 draft。

### 2.4 Add Formula 保存（`data_capture_formula`）

**`POST /api/datacapture-summary/formula/save`**

请求（`DataCaptureSummaryDTO`，camelCase）：

```json
{
  "tenantId": 42,
  "processId": 15,
  "processCode": "SALARY",
  "idProduct": "AAA",
  "accountId": 8,
  "currencyId": 3,
  "description": "Win",
  "sourceColumns": "$2,$3",
  "formula": "$2+$3",
  "formulaOperators": "$2+$3",
  "sourcePercent": "1",
  "enableSourcePercent": true,
  "enableInputMethod": false,
  "rowIndex": 3
}
```

- `processId` — process 主键（有则优先）  
- `processCode` — Bank Summary 常只有 code（如 `SALARY`）；无 `processId` 时服务端按 code 解析，缺省 BANK process 则自动创建（同 draft save）

**落库规则（Add）：**

| 条件 | 行为 |
|------|------|
| 该 `tenantId + processId + idProduct` 尚无 MAIN，或 MAIN 的 `account_id` 为空 | 写 **MAIN**（有空 MAIN 则 UPDATE 填入；否则 INSERT） |
| 已有带 `account_id` 的 MAIN | 插入 **SUB**（`parent_id_product = idProduct`，`sub_order = max+1`） |

响应 `data` 带回 `id`、`productType`（`MAIN`/`SUB`）、`subOrder`、`formulaVariant` 等。

前端：Summary **Add Formula**（`mode === "new"`）走 `saveAddFormulaSpring`（传 `processId` + `processCode`）；成功后关闭弹窗。

自测：
1. Bank SALARY（无数字 processId）→ Add Formula Save → body 带 `processCode:"SALARY"`，不再报 `processId is required`；成功后弹窗关闭。
2. 空 main 行 → DB `product_type=MAIN`；同 product 再 Add → `SUB`。

### 2.6 Edit Formula / Source 行内更新（按行 `id`）

**`POST /api/datacapture-summary/formula/update`**

```json
{
  "id": 12,
  "tenantId": 42,
  "accountId": 8,
  "currencyId": 3,
  "formula": "$2+$3",
  "formulaOperators": "$2+$3",
  "sourcePercent": "0.5",
  "enableSourcePercent": true,
  "description": "Win"
}
```

| 规则 | 说明 |
|------|------|
| 定位 | 优先 `id`（`templateId`）；**Bank 无 id 时**用 `processCode` + `productType` + `idProduct` + `accountId`（SUB 再加 `parentIdProduct` + `subOrder`） |
| 身份字段 | **不改** `product_type` / `parent_id_product` / `sub_order` / `id_product` |
| MAIN / SUB | SUB 编辑只更新自己，不影响 MAIN 或其他 SUB |
| Edit Formula | 铅笔打开 → 回填当前 row → Save → `saveUpdateFormulaSpring` → 成功关弹窗 |
| Source 行内 | 双击 Source → **Enter 或离开单元格（blur）** → `saveUpdateFormulaSpring`；未变不请求 |

自测：
1. Bank SALARY（无数字 processId / 无 templateId）→ Edit Formula Save → body 带 `processCode:"SALARY"` + `productType` + `accountId`，不再报 `Formula id is required`；成功关弹窗。
2. MAIN 铅笔 Edit → 只更新该 MAIN；SUB 铅笔 Edit → 只更新该 SUB。
3. 双击 Source 改为 `0.5` → Enter/blur → `POST .../formula/update`，DB `source_percent=0.5`。

### 2.7 Formula 删除（硬删；无 subOrder 重排）

**`POST /api/datacapture-summary/formula/delete`**

```json
{
  "tenantId": 42,
  "processId": 15,
  "processCode": "SALARY",
  "items": [
    { "id": 12 },
    {
      "productType": "SUB",
      "idProduct": "AAA",
      "parentIdProduct": "AAA",
      "accountId": 9,
      "subOrder": 1
    }
  ]
}
```

| 规则 | 说明 |
|------|------|
| 定位 | 每项优先 `id`（`templateId`）；无 id 时用 business key（同 update） |
| 删除 | 硬 `DELETE` `data_capture_formula`；已不存在则跳过（幂等） |
| MAIN / SUB | 都只删对应 DB 行；**不做** subOrder 重排 |
| UI | 先调 Spring，再本地：MAIN（及无父级的孤儿行）清数据留骨架；真 SUB 整行移除 |
| Process | 按 `processId` / `processCode` **查找**，不自动创建 Bank process |
| 前端 | `deleteFormulasSpring`（`summarySaveTemplatePure.js`）← `useSummaryPageActionsPure.handleDeleteSelected`；**不再**走 PHP `delete_template` / `syncSubOrderTemplates` |

响应：

```json
{
  "success": true,
  "message": "Formula Deleted Successfully",
  "data": { "deletedCount": 2, "deletedIds": [12, 15] }
}
```

请求/响应均使用 **`DataCaptureSummaryDTO`**（`items` 为待删列表；`deletedCount` / `deletedIds` 为结果字段）。校验文案与 Spring 对齐：`Tenant Id is required` / `Process Id is required` / `items are required` 等。

自测：
1. 勾选 MAIN → Delete → Network `POST .../formula/delete` → DB 行消失，UI **立刻**留空骨架（Id Product 仍在，无需手动 Refresh）。
2. 勾选 SUB → Delete → 仅该 SUB 从 DB/UI 移除；其余 SUB 的 `sub_order` **不**重排。
3. Bank（仅 `processCode`）→ body 带 `processCode`，无 `Formula id is required`。

### 2.5 Add/Edit Formula — Account / Currency catalog

弹窗打开时 `useSummaryEditFormulaPure` 调 `fetchSummaryFormCatalog(captureScope, companyId)`：

| 数据 | 优先 | 回退 |
|------|------|------|
| **Accounts** | `POST /api/account/list?tenant_id=` → `filterAccountListRows`（仅 active） | PHP `summary_catalog_api.php`（Spring 为空时） |
| **Currencies**（未选 account 时的初始下拉） | `POST /api/currency/list?tenant_id=` → `fetchCaptureCurrenciesByTenantId` | 同上 PHP catalog |

`tenantId` = `resolveDataCaptureEffectiveTenantId(captureScope, companyId)`（pill / scope 的数字 tenant id）。

**选中 Account 后（立即）：**

| 步骤 | 行为 |
|------|------|
| 请求 | `POST /api/currency/available?tenant_id=&account_id=`（`fetchAvailableCurrencies`） |
| 下拉选项 | 仅 `is_linked === true` 的币别（该 account 已关联的，如 Edit Account 里的 MYR） |
| 默认选中 | 单币别直接选中；多币别优先 MYR，否则第一项 |

展示文案：`formatSummaryAccountDisplay` 读 Spring 归一化字段 `id` / `account_id` / `name` / `role`。

自测：
1. Summary → Add Formula → 点 Account → Network 可见 `POST /api/account/list?tenant_id=`，下拉出现 active 账户。
2. 选中已关联 MYR 的账户（如 BK SHA 2）→ 立即 `POST /api/currency/available?...&account_id=` → Currency 下拉出现 MYR 并自动选中。

### 2.1 Games 表单 API

**`POST /api/datacapture/games/form`**

请求（`DataCaptureGameDTO`）：

```json
{
  "tenantId": 42,
  "captureDate": "2026-07-27",
  "id": 15
}
```

- `tenantId` — 必填，subsidiary 或 group entity 的 tenant pk  
- `captureDate` — 必填，`YYYY-MM-DD`  
- `id` — 可选，process 表主键；有则返回 `selectedProcess` 详情  

响应：

```json
{
  "success": true,
  "message": "success",
  "data": {
    "tenantId": 42,
    "captureDate": "2026-07-27",
    "processes": [
      {
        "id": 15,
        "processId": "WM",
        "descriptionName": "FOOTBALL",
        "processDisplay": "WM (FOOTBALL)"
      }
    ],
    "selectedProcess": {
      "id": 15,
      "processId": "WM",
      "currencyId": 3,
      "currencyCode": "MYR",
      "removeWord": "TEST",
      "replaceWordFrom": "A",
      "replaceWordTo": "B",
      "remark": "process config remark",
      "descriptionNames": ["FOOTBALL", "BASKETBALL"]
    }
  }
}
```

前端映射：

| Spring 字段 | UI 用途 |
|-------------|---------|
| `id` | process 主键（select value） |
| `processId` | 业务码（原 PHP `process_id`） |
| `processDisplay` | 下拉展示文案 |
| `descriptionName` | 列表行上的单一描述摘要 |
| `descriptionNames` | 多选 description 回填 |
| `currencyId` / `currencyCode` | 币别下拉 |
| `removeWord` / `replaceWordFrom` / `replaceWordTo` | 词过滤字段 |
| `remark` | process 配置备注（选中 process 时回填） |

校验错误（HTTP 200 + `success: false`）示例：

- `Not logged in`
- `tenantId is required`
- `captureDate is required`
- `Process not found`

### 2.8 Summary 最终 Submit（`data_captures` + `data_capture_line` + `transactions`）

**`POST /api/datacapture-summary/submit`**

一次请求 = 一个事务：写 `data_captures`（头）+ `data_capture_line`（逐行）+ `transactions`（非零行 WIN/LOSE）+ GAME 才写 `process_submitted`。**不分批**（Spring 无 PHP `post_max_size` 限制，见 `docs/TABLE_MIGRATION.md` §4）。

请求（`DataCaptureSummarySubmitDTO`）：

```json
{
  "tenantId": 52,
  "category": "BANK",
  "processId": null,
  "processCode": "SALARY",
  "captureDate": "2026-08-07",
  "currencyId": 13,
  "remark": "",
  "removeWord": "",
  "replaceWordFrom": "",
  "replaceWordTo": "",
  "lines": [
    {
      "productType": "MAIN",
      "idProduct": "AAA",
      "accountId": 8,
      "currencyId": 13,
      "sourcePercent": "1",
      "enableSourcePercent": true,
      "formula": "1111 * 2222",
      "processedAmount": "2469531.000000",
      "rateValue": null
    }
  ]
}
```

响应：`{ "success": true, "message": "...", "data": { "captureId": 901 } }`

| 规则 | 说明 |
|------|------|
| 金额重算 | 后端**不信**客户端 `processedAmount` 原值——用 `SummaryAmountFormat.finalizeProcessedAmount` 强制截断（ROUND_DOWN 6 位）后才落库；见 §3.1 |
| Submit 门槛 | 所有行重算后金额高精度求和，`SummaryAmountFormat.isTotalWithinSubmitTolerance`（±0.05）不过则整单拒绝，不写任何表 |
| GAME 拦截 | `category` 解析为 GAME 时，若当日该 process 已提交（`process_submitted`）直接拒绝；**BANK 不受此限**，可重复提交 |
| Process 解析 | 有 `processId` 优先；否则按 `processCode` 解析（Bank 缺省自动建，同 §2.4） |
| `transactions` 记录 | 每个非零行一笔：`amount` 存绝对值，`transactionType` 由正负号定 `WIN`/`LOSE`；`description` 格式 `"{processCode}: {formula}"`（见 `docs/transaction-description-rules.md`「WIN / LOSE」节） |
| Customer/Domain Report | 这次只保证落库；报表页面本身未实现。**Bank 提交不产出 report 数据**——报表查询将来加 `data_captures.category = 'GAME'` 过滤，不需要额外字段 |

**前端调用（Count-frontend）：**

| 文件 | 职责 |
|------|------|
| `datacapturesummary/lib/summaryApi.js` | `submitSummaryToSpring(payload)` — 单次 POST，不分批 |
| `datacapturesummary/submit/summarySubmitExecution.js` | `executeSummarySubmit(...)`：**Games/Bank 公司范围**（含 C168/bank-only 的 group payroll UI）→ 新 Spring 一次性提交；**真正的 AP/IG group ledger**（`isGroupLedgerCapture` 为 true）→ 仍走旧 PHP `submitSummaryPayload` + 分批（因为该场景的 tenant/process 解析仍依赖 PHP-only 的 `get_group_process_id`，见 §4） |
| `datacapturesummary/submit/buildSubmitRowsFromModel.js` | **未改**——仍产出旧字段命名的行对象；`summarySubmitExecution.js` 内的 `toSpringLine()` 负责改名/裁剪成 `DataCaptureLineDTO` 形状，不再往下游传 `account`/`currency`（展示用字符串）、`templateId`/`templateKey`/`subOrder`/`rateChecked`/`batchSelection`/`inputMethod` 等 Spring 不需要的字段 |

**已知限制（有意保留，非本次范围）：** 真 AP/IG group ledger 提交仍是 PHP 路径（`get_group_process_id` 未迁移）；该分支的批次二分重试（size-error split）也一并简化掉了，只保留固定大小分批——量级极少触发，之后要迁移就直接连 Spring 一起换掉。

自测：
1. BK company（如 C168）→ Bank SALARY + MYR + 表格 → Submit → Network 只有一次 `POST /api/datacapture-summary/submit`（无分批）；响应带 `captureId`。
2. 同一 BK SALARY 当天再次 Submit → 正常成功（不拦截）。
3. Games process 当天 Submit 一次后，再选同一 process → Data Capture 页 process 下拉不再出现它（`process_submitted` 生效）；若绕过 UI 直接再 Submit → 后端拒绝 `This process has already been submitted today`。
4. 合计故意超 ±0.05 → Submit 被拒绝，`transactions`/`data_capture_line`/`data_captures` 均无新增行。

---

## 3. 前端改动文件（2026-07-27；金额算法 2026-07-29；Summary Submit 切 Spring 2026-08-07）

| 文件 | 说明 |
|------|------|
| `pages/datacapture/lib/dataCaptureSpringApi.js` | Spring 直调封装 |
| `pages/datacapture/lib/dataCaptureTenant.js` | scope → `tenantId` 解析 |
| `pages/datacapture/lib/dataCaptureApi.js` | Games form / 币别 / description / permissions 改 Spring |
| `pages/datacapture/lib/dataCaptureCompanyAccess.js` | 去掉 `domain_api.php`；用 switch-tenant flags |
| `pages/datacapture/hooks/useDataCaptureFormEngine.js` | camelCase 字段 + scope 传参 |
| `pages/datacapture/hooks/useDataCaptureCategoryPermissions.js` | key 改为 `tenantId` |
| `pages/datacapture/DataCapturePage.jsx` | category permissions 用 `categoryTenantId` |
| `pages/datacapturesummary/table/summaryRowAmount.js` | Summary 金额算法（与后端 `SummaryAmountFormat` 对齐：rate 8 位截断 → 最终 6 位截断；展示 HALF_UP 2） |
| `pages/datacapturesummary/submit/buildSubmitRowsFromModel.js` | Submit `processedAmount` 为 6 位 plain 字符串 |
| `pages/datacapturesummary/submit/summarySubmitTotalPure.js` | 合计 ±0.05 门槛 |
| `pages/datacapturesummary/submit/summarySubmitRowGuard.js` | 行守卫用 6 位 amount |
| `pages/datacapturesummary/formula/summarySaveTemplatePure.js` | `last_processed_amount` 6 位截断 |
| `pages/datacapturesummary/formula/editFormulaFormState.js` | 状态存真值/6 位；display 才 round 2 |
| `pages/datacapturesummary/lib/summaryApi.js` | 新增 `submitSummaryToSpring()`（`POST /api/datacapture-summary/submit`，单次不分批）；原 `submitSummaryPayload()` 保留给 group-ledger 分支用 |
| `pages/datacapturesummary/submit/summarySubmitExecution.js` | 重写：按 `isGroupLedgerCapture` 分流——Games/Bank 公司范围走新 Spring 一次性提交（`executeSpringSubmit`）；真 AP/IG group ledger 走保留下来的旧 PHP 分批逻辑（`executeLegacyGroupLedgerSubmit`）；新增 `toSpringLine()` 做行字段改名/裁剪 |

Group/Company picker **未改 UI 行为**：仍用 `fetchOwnerCompaniesAll()`（底层已是 `tenant-accessible`）。

### 3.1 Summary processed amount（前后端同管线）

完整规则与示例见 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」。

要点：

1. **截断 ROUND_DOWN（向零）**，不是 Transaction 那套 HALF_UP-到上限。
2. 走 rate → 中间 **8** 位；最终入库 / Submit payload → **6** 位（8→6 先截断再算最终值）。
3. 前端格子 HALF_UP **2** 仅展示；**禁止**用 display 值作 payload / 合计 / 模板 fallback。
4. 合计 HALF_UP 2 后须在 **±0.05** 才可 Submit。
5. 后端：`SummaryAmountFormat` + `TransactionMoneyFormat.truncate*`；前端：`summaryRowAmount.js` 的 `resolveSubmitProcessedAmount`。

---

## 4. 仍走 PHP 的部分（待后端）

| 能力 | 旧 PHP | 说明 |
|------|--------|------|
| 当日已提交列表 | `api/datacapture/submissions_api.php` | 右侧 submitted 面板 |
| Group payroll process id 解析 | `get_group_process_id` | **仅真 AP/IG group ledger**（`isGroupLedgerCapture` 为 true）；C168 / bank-only company payroll 已随 Submit 一起切 Spring（见 §2.8），不再依赖此接口 |
| Group 币别聚合 | `get_scope_account_currencies_api.php` | group ledger scope |
| Submit / Summary | ~~`api/datacapture_summary/summary_submit_api.php`~~（Games/Bank 公司范围） | **Spring** `POST /api/datacapture-summary/submit`（见 §2.8）：一次事务写 `data_captures`+`data_capture_line`+`transactions`（+GAME 写 `process_submitted`）。**仅真 AP/IG group ledger 仍走 PHP**（因 tenant/process 解析依赖上一行未迁移的 `get_group_process_id`），沿用旧分批提交 |
| Bank draft 表格 | ~~`group_capture_draft_api.php`~~ | **Spring** `POST /api/datacapture/bank/draft/save|get`（`data_capture_draft*`）；PROFIT 排除 |
| Add Formula 保存 | ~~`summary_templates_api.php?action=save_template`~~（Add 路径） | **Spring** `POST /api/datacapture-summary/formula/save`（`data_capture_formula`）；MAIN 无数据→写 MAIN，已有→插 SUB |
| Edit Formula / Source 更新 | ~~`summary_templates_api.php?action=save_template`~~（Edit / Source） | **Spring** `POST /api/datacapture-summary/formula/update`（按 `id`；SUB 只改自己；Source 为 Enter/blur） |
| Formula 删除 | ~~`summary_templates_api.php?action=delete_template`~~ | **Spring** `POST /api/datacapture-summary/formula/delete`（`deleteFormulasSpring`；MAIN 清骨架 / SUB 移除；无 subOrder 重排） |
| Formula Account 下拉 | ~~`summary_catalog_api.php`~~（优先路径） | **Spring** `POST /api/account/list`；PHP catalog 仅作空结果回退 |
| 选中 Account 后币别 | ~~`account_currency_api.php`~~ | **Spring** `POST /api/currency/available?tenant_id=&account_id=`（仅 linked） |

---

## 5. 本地验证清单

1. 登录后打开 `/datacapture`，Group/Company pill 数据来自 `tenant-accessible`。
2. 选 subsidiary company → Network 可见 `POST /api/datacapture/games/form`（仅 `tenantId` + `captureDate`）。
3. 选 process → 同上接口带 `id`；表单回填 `currencyId`、`descriptionNames`、词过滤字段。
4. 打开 Description 弹窗 → `POST /api/process/list-description`（body 为 tenant id 数字）。
5. 切换 company → `POST /auth/switch-tenant`；Games/Bank pill 随 `has_game` / `has_bank` 变化。
6. **Bank Phase 1：** bank-only company（如 BK）→ Process SALARY + Currency + 表格 → Submit → Summary 表头/Id Product 行；F12 localStorage 可见 `category:"BANK"`、`processCode`、`capturedTableData:{tenantId}`。
7. **Bank Phase 2：** Submit 后 Network 可见 `POST /api/datacapture/bank/draft/save`；再选 SALARY+同 currency → `POST .../bank/draft/get` 回填表格；PROFIT 不出现 save。
8. **Add Formula：** Summary 空 main 行点 Add Formula → Save → `POST /api/datacapture-summary/formula/save`，`data_capture_formula.product_type=MAIN`；同 product 再 Add → `SUB`。
9. **Formula Account 下拉：** 打开 Add/Edit Formula → Network 可见 `POST /api/account/list?tenant_id=`；Account 搜索框下方列出 active 账户。
10. **选中 Account → Currency：** 选 BK SHA 2（Edit Account 已挂 MYR）→ 立即 `POST /api/currency/available?...&account_id=`；Currency 出现 MYR 并自动选中。
11. **Edit Formula：** 铅笔打开 → 改字段 Save → `POST .../formula/update`；SUB 只更新自己；成功关弹窗。
12. **Source 行内：** 双击 Source → 改值 → Enter 或 blur → `POST .../formula/update` 写 `source_percent`。
13. **Formula 删除：** 勾选 → Delete → `POST .../formula/delete`；MAIN 清 UI 骨架 + DB 硬删；SUB 整行移除；无 subOrder 重排。
14. **Summary 最终 Submit：** Games/Bank 公司范围 → Summary 点 Submit → Network 仅一次 `POST /api/datacapture-summary/submit`（不再分批）；成功后 `transactions`/`data_captures`/`data_capture_line` 三张表都能查到新行；详细自测见 §2.8。

---

## 6. 维护约定

- 新增 Data Capture Spring 接口时：**先更新本文 + `DataCaptureGameDTO`**，再改 `dataCaptureSpringApi.js`。
- 勿在 Spring 层恢复 snake_case 兼容；前端在 API 边界做 normalize（仅读取时兼容旧 session storage 可保留双读）。
- 与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md) 第 2 节迁移表同步更新 Data Capture 行状态。
- 改 Summary **processed amount** 精度 / rate / ±0.05 门槛时：同步更新 [transaction-amount-precision.md](./transaction-amount-precision.md)「Data Capture Summary」、后端 `SummaryAmountFormat`、前端 `summaryRowAmount.js`（及 Submit / 模板写入路径）。
