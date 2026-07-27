# Games Process List — Spring API 对齐说明

> **前端仓库**：`../Count-frontend/`  
> **后端前缀**：`/api/process/*`、 `/api/currency/*`、 `GET /auth/tenant-accessible`  
> **最后更新**：2026-07-27

---

## 1. 原则

- **前端对齐 Spring**，不要求后端恢复 PHP 字段（camelCase、`tenantId`、JSON RequestBody）。
- **Tenant 模型**：UI pill 的数字 `id` = `tenant.id`；列表/写操作 **RequestBody 传 `tenantId`**，**不出现在 URL query**。
- **API 层命名**：使用 `tenantId` / `fetchProcessListByTenantId` 等；**不在 API 模块使用 `scope` / `company_id` / `group_id` 参数**（Group/Company pill 仍用 `tenant-accessible` 做 UI 筛选，见 §5）。
- **Games 行**：列表 normalize 时过滤 `process.category === 'BANK'`（BANK 流程在 Bank Process List 页）。

---

## 2. Spring 端点一览

| 能力 | 方法 | 路径 | Body |
|------|------|------|------|
| 列表 | POST | `/api/process/process-list` | JSON 数字 `tenantId` |
| 表单 meta（币别） | POST | `/api/currency/list?tenant_id=` | — |
| Description 列表 | POST | `/api/process/list-description` | JSON 数字 `tenantId` |
| 新增 Description | POST | `/api/process/add-description` | `{ tenantId, name }` |
| 删除 Description | POST | `/api/process/delete-description` | `{ id, tenantId }` |
| 新增 Process | POST | `/api/process/add-process` | `ProcessDTO` 扁平字段 + `category: "GAME"` |
| 更新 Process | POST | `/api/process/update-process` | `{ id, tenantId, currencyId, descriptionIds[], dayOfWeeks[], … }` |
| 切换状态 | POST | `/api/process/update-status` | `{ id, tenantId }` |
| 删除 Process | POST | `/api/process/delete-process` | `{ id, tenantId }`（多选由前端循环） |
| Tenant 列表 | GET | `/auth/tenant-accessible?all=1` | — |
| 切换活动 tenant | POST | `/auth/switch-tenant?tenant_id=` | — |

成功响应：`success === true`（或 `status === "success"`）。

---

## 3. 列表：`ProcessDTO` → 表格行

Spring 返回结构化行（非 PHP 扁平 snake_case）：

```json
{
  "id": 15,
  "currencyCode": "MYR",
  "process": {
    "id": 15,
    "tenantId": 42,
    "category": "GAME",
    "code": "WM",
    "currencyId": 3,
    "removeWord": "TEST",
    "replaceWordFrom": "A",
    "replaceWordTo": "B",
    "remark": "",
    "status": "ACTIVE",
    "createdBy": "admin1",
    "updatedBy": "admin1",
    "createdAt": "2026-01-01T10:00:00",
    "updatedAt": "2026-01-02T11:00:00"
  },
  "processDescriptions": [{ "id": 1, "name": "FOOTBALL" }],
  "processDays": [{ "id": 10, "processId": 15, "dayOfWeek": 1 }]
}
```

前端 `normalizeProcessListItem()`（`processListHelpers.js`）映射：

| Spring | 表格/UI |
|--------|---------|
| `process.code` | `process_name` |
| `processDescriptions[]` | `description`（逗号拼接） |
| `processDays[].dayOfWeek` | `day_use`（`MON,TUE,…`） |
| `currencyCode` | `currency` |
| `process.status` | `status`（`ACTIVE` / `INACTIVE`，与 Spring enum 一致） |
| `category === 'BANK'` | 该行丢弃（Games 页不展示） |

**搜索 / Show Inactive / Show All**：Spring 列表暂无 query 参数；客户端 `applyProcessListFilters()`（`processRoutePrefetch.js` → `fetchGamesProcessListSlice`）。

---

## 4. Add / Update / Status / Delete

### 4.1 新增（Add）

```json
{
  "tenantId": 42,
  "code": "WM",
  "category": "GAME",
  "currencyId": 3,
  "descriptionIds": [1, 2],
  "dayOfWeeks": [1, 4],
  "removeWord": "A,B",
  "replaceWordFrom": "X",
  "replaceWordTo": "Y",
  "remark": ""
}
```

- `dayOfWeeks`：`1=Mon … 7=Sun`（与 `PROCESS_WEEKDAY_OPTIONS` 一致）。
- **Multi-Process Add**：前端对每个 code 循环调用 `addProcess()`（不再走 PHP 批量 `selected_processes`）。
- **Copy From**：从当前列表行本地读取字段填充表单（不再调 PHP `copy_from`）。

### 4.2 更新（Edit）

- **打开 Edit**：不调 `get_process`；用当前页 `rows` + `buildEditFormFromListRow()`。
- **提交**：`updateProcess(tenantId, { id, currencyId, descriptionIds, dayOfWeeks, … })`；`code` 只读不更新。

### 4.3 状态 / 删除

- **Status 枚举**：Games Process **仅** `ACTIVE` | `INACTIVE`（`Process.Status`；DB `process.status` 同值）。**无 `WAITING`** — `WAITING` 属于 **Bank Process**（`BankProcess.Status`），不在本页使用。
- **展示**：表格 badge 显示 **Active** / **Inactive**（英文）；内部 state / 过滤 / API 对齐后端大写 enum。
- **切换**：点击 status badge → `updateProcessStatus(tenantId, processId)` → 服务端在 `ACTIVE` ↔ `INACTIVE` 间 toggle；读 `data.status`。
- **Delete**：仅 `INACTIVE` 可删；`deleteProcess(tenantId, id)` 逐条循环。

---

## 5. Tenant 选择与切换（UI 仍显示 Group / Company pill）

| 层 | 实现 |
|----|------|
| Pill 数据源 | `fetchOwnerCompaniesAll()` → `GET /auth/tenant-accessible` |
| 数字 id | `tenant.id`（内部 state 仍可能叫 `companyId`，语义为 tenant pk） |
| 切换 tenant | `syncCompanySessionApi(tenantId)` → `POST /auth/switch-tenant` |
| Games ↔ Bank 路由 | `resolveTenantIsBankOnly(tenantId, sessionMe)` + switch-tenant 返回的 `has_game` / `has_bank` |

**不在 API URL 或 RequestBody 中使用** `company_id` / `group_id` / `scope` / `permission=Games`。

---

## 6. 前端文件（2026-07-27）

| 文件 | 职责 |
|------|------|
| `pages/processlist/processListApi.js` | Spring 直调：list / description CRUD / add / update / status / delete / `fetchProcessFormMeta` |
| `pages/processlist/processListHelpers.js` | `normalizeProcessListItem`、`applyProcessListFilters`、`buildEditFormFromListRow`、`PROCESS_WEEKDAY_OPTIONS` |
| `pages/processlist/processRoutePrefetch.js` | `fetchGamesProcessListSlice` → Spring list + 客户端过滤 |
| `pages/processlist/ProcessListPage.jsx` | 页面编排；mutations 全 Spring |
| `pages/processlist/components/ProcessFormModal.jsx` | 表单 UI；`scopeTenantId` prop（传给 RemoveWordChipInput 作 tenant 隔离） |

---

## 7. 已移除的 PHP 调用

| 旧 PHP | 替代 |
|--------|------|
| `processlist_api.php?action=list` | `POST /api/process/process-list` |
| `addprocess_api.php`（form meta） | `fetchProcessFormMeta` + `fetchProcessListByTenantId`（existingProcesses 来自列表行） |
| `addprocess_api.php`（add / description） | `addProcess` / `addProcessDescription` |
| `processlist_api.php?action=get_process` | 列表行本地回填 |
| `processlist_api.php?action=update_process` | `updateProcess` |
| `toggle_process_status_api.php` | `updateProcessStatus` |
| `delete_processes_api.php` | 循环 `deleteProcess` |
| `addprocess_api.php?action=copy_from` | `buildCopyFromFormPatchFromRow` |

---

## 8. 本地验证清单

1. 打开 `/process-list`，Network 可见 `POST /api/process/process-list`，body 为数字 tenant id。
2. Add：提交后 `POST /api/process/add-process`，body 含 `tenantId`、`code`、`category:"GAME"`。
3. Edit：打开时不应出现 `get_process`；保存走 `update-process`。
4. 点 Status：`POST /api/process/update-status`。
5. 删除 Inactive：`POST /api/process/delete-process`（每条一次）。
6. Description 弹窗：`list-description` / `add-description` / `delete-description`。
7. 切换 pill：`POST /auth/switch-tenant?tenant_id=`。

---

## 9. 维护约定

- 新增 Process 相关 Spring 字段时：**先改后端 DTO + 本文**，再改 `normalizeProcessListItem` 与表单映射。
- 与 [`frontend-springboot-migration.md`](./frontend-springboot-migration.md) 第 9 节、`datacapture-spring-api.md` 的 tenant 约定保持一致。
- 服务端 list 若将来支持 search/status query，可删除客户端 `applyProcessListFilters` 中对应逻辑。

---

## 10. Process.Status 与 Bank Process 的区别

| 模块 | Java enum | DB | Games Process List 页 |
|------|-----------|-----|------------------------|
| **Games Process** | `Process.Status` → `ACTIVE`, `INACTIVE` | `process.status` ENUM | ✅ 使用；badge 可点击 toggle |
| **Bank Process** | `BankProcess.Status` → `WAITING`, `ACTIVE`, `OFFICIAL`, … | `bank_process.status` | ❌ 不在本页；见 Bank Process List |

历史 PHP `process` 表曾用小写 `active`/`inactive`；迁移后须与 Spring 一致（见 `sql/migrate_enums_to_uppercase.sql`）。前端 **不要** 为 Games Process 引入 `WAITING` 分支或 `status-waiting` badge。
