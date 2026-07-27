# Data Capture — Spring API 对齐说明

> **前端仓库**：`../Count-frontend/`  
> **后端契约**：`DataCaptureGameDTO` + tenant 模型（无 JSON / 无 `scope_*`）  
> **最后更新**：2026-07-27

---

## 1. 原则

- **前端对齐 Spring**，不要求后端迁就 PHP 字段（camelCase、`tenantId`、JSON body）。
- **Group / Company pill 的 `id` = `tenant.id`（数字）**；code 仅用于展示与 parent group 筛选。
- 公司列表与切换统一走 **`GET /auth/tenant-accessible`** 与 **`POST /auth/switch-tenant`**。

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

---

## 3. 前端改动文件（2026-07-27）

| 文件 | 说明 |
|------|------|
| `pages/datacapture/lib/dataCaptureSpringApi.js` | Spring 直调封装 |
| `pages/datacapture/lib/dataCaptureTenant.js` | scope → `tenantId` 解析 |
| `pages/datacapture/lib/dataCaptureApi.js` | Games form / 币别 / description / permissions 改 Spring |
| `pages/datacapture/lib/dataCaptureCompanyAccess.js` | 去掉 `domain_api.php`；用 switch-tenant flags |
| `pages/datacapture/hooks/useDataCaptureFormEngine.js` | camelCase 字段 + scope 传参 |
| `pages/datacapture/hooks/useDataCaptureCategoryPermissions.js` | key 改为 `tenantId` |
| `pages/datacapture/DataCapturePage.jsx` | category permissions 用 `categoryTenantId` |

Group/Company picker **未改 UI 行为**：仍用 `fetchOwnerCompaniesAll()`（底层已是 `tenant-accessible`）。

---

## 4. 仍走 PHP 的部分（待后端）

| 能力 | 旧 PHP | 说明 |
|------|--------|------|
| 当日已提交列表 | `api/datacapture/submissions_api.php` | 右侧 submitted 面板 |
| Group payroll process id 解析 | `get_group_process_id` | C168 / bank payroll |
| Group 币别聚合 | `get_scope_account_currencies_api.php` | group ledger scope |
| Submit / Summary | `api/datacapture/*`、`api/summary/*` | 提交与汇总页 |
| Bank draft 表格 | ~~`group_capture_draft_api.php`~~ | **Spring** `POST /api/datacapture/bank/draft/save|get`（`data_capture_draft*`）；PROFIT 排除 |

---

## 5. 本地验证清单

1. 登录后打开 `/datacapture`，Group/Company pill 数据来自 `tenant-accessible`。
2. 选 subsidiary company → Network 可见 `POST /api/datacapture/games/form`（仅 `tenantId` + `captureDate`）。
3. 选 process → 同上接口带 `id`；表单回填 `currencyId`、`descriptionNames`、词过滤字段。
4. 打开 Description 弹窗 → `POST /api/process/list-description`（body 为 tenant id 数字）。
5. 切换 company → `POST /auth/switch-tenant`；Games/Bank pill 随 `has_game` / `has_bank` 变化。
6. **Bank Phase 1：** bank-only company（如 BK）→ Process SALARY + Currency + 表格 → Submit → Summary 表头/Id Product 行；F12 localStorage 可见 `category:"BANK"`、`processCode`、`capturedTableData:{tenantId}`。
7. **Bank Phase 2：** Submit 后 Network 可见 `POST /api/datacapture/bank/draft/save`；再选 SALARY+同 currency → `POST .../bank/draft/get` 回填表格；PROFIT 不出现 save。

---

## 6. 维护约定

- 新增 Data Capture Spring 接口时：**先更新本文 + `DataCaptureGameDTO`**，再改 `dataCaptureSpringApi.js`。
- 勿在 Spring 层恢复 snake_case 兼容；前端在 API 边界做 normalize（仅读取时兼容旧 session storage 可保留双读）。
- 与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md) 第 2 节迁移表同步更新 Data Capture 行状态。
