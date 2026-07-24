# Bank Process Maintenance — List / Delete（Spring）

Bank Process Maintenance 页面列表与软删除约定。修改 API、过滤规则或前端契约时，**同步更新本文档**。

相关：

- Payment Maintenance（对照实现）：[`payment-maintenance-list-delete.md`](./payment-maintenance-list-delete.md)
- 侧边栏入口：[`maintenance-navigation.md`](./maintenance-navigation.md)

---

## 1. 范围与原则

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/bankprocess/*` |
| 数据源 | `transactions`（活数据）+ `transactions_deleted`（软删归档） |
| 租户 | **一律 `tenantId`**（Company pill numeric id） |
| **含** | Bank Process **已入账**行：`bank_process_posted_id IS NOT NULL`，`transaction_type IN (WIN, LOSE)`，`bpap.outcome = POSTED` |
| **不含** | Payment Maintenance 手动流水（`bank_process_posted_id IS NULL`） |
| 归档 | 与 Payment 共用 `transactions_deleted`（`bank_process_posted_id` 有值 = BP Maintenance 归档） |

---

## 2. 列表 UI 列（与旧 PHP / 截图一致）

| 表头 | 字段来源 |
|------|----------|
| No. | 行号 |
| Dts Created | `created_at` |
| Account | To account code（`toAccountCode`） |
| From | From account code；无则 `bank_process.card_owner` |
| Amount | `currencyCode` + `amount` |
| Description | `description` |
| Remark | `remark` |
| Submitted By | `createdBy`（login_id） |
| Deleter | 软删行：`{deletedBy} ({deletedAt})` |
| Checkbox | 活数据可勾选；**同 Post 批次**联动勾选（见前端 batch key） |

软删行：`deleted=true` → 红色划线（`maintenance-row-deleted`），`is_deleted=1`，不可再勾选。

**无 Category pills**（见 `maintenance-navigation.md` §9）。

---

## 3. List API

### 3.1 端点

`POST /api/maintenance/bankprocess-maintenance/list`

### 3.2 请求（`BankProcessMaintenanceRequest`）

```json
{
  "tenantId": 12,
  "dateFrom": "01/01/2026",
  "dateTo": "24/07/2026",
  "currencyCodes": ["MYR"],
  "q": "CIMB"
}
```

无 `transactionType`（Bank Process Maintenance 固定 WIN/LOSE 入账行）。

| 字段 | 说明 |
|------|------|
| `tenantId` | 必填，`> 0` |
| `dateFrom` / `dateTo` | 闭区间；过滤 **`transaction_date`** |
| `currencyCodes` | 空数组 = 全部币种 |
| `q` | 模糊：Account / From / card_owner / description / remark / submitter（归档含 deleter） |

### 3.3 响应行（`BankProcessMaintenanceRow`）

| JSON 字段 | 含义 |
|-----------|------|
| `id` | 活数据 `transactions.id`；归档为原 `transaction_id` |
| `transactionType` | `WIN` / `LOSE` |
| `createdAt` | Dts Created |
| `toAccountCode` | Account 列 |
| `fromAccountCode` | From 列 |
| `amount` / `currencyCode` | Amount |
| `description` / `remark` | 描述 / 备注 |
| `createdBy` | Submitted By |
| `deleted` / `deletedBy` / `deletedAt` | 软删展示 |
| `bankProcessId` | `bank_process.id`（前端 `source_bank_process_id`，批次勾选） |
| `periodType` | `bank_process_accounting_posted.period_type` |
| `transactionDate` | `transaction_date`（批次 key 之一） |

Service 合并 live + archived，按 `createdAt` / `id` 降序。

### 3.4 前端

- `bankprocessMaintenanceLogic.js`
  - `buildSpringBankprocessMaintenanceRequest`
  - `normalizeSpringBankprocessMaintenanceRow`
  - `searchBankprocessData` → Spring list
  - `bankprocessMaintenanceBatchKey` / `toggleBankprocessMaintenanceBatchSelection`
- 币种：`POST /api/currency/list?tenant_id=`

---

## 4. Delete API（软删）

### 4.1 行为

`MaintenanceServiceImpl.deleteBankProcessMaintenanceRows`（`@Transactional`）：

1. 校验登录、非只读、`tenantId`、`transactionIds`
2. `resolveBankProcessDeletableBatch`：加载选中行 → 过滤可删（`bank_process_posted_id IS NOT NULL`、`WIN`/`LOSE`、`approval_status = APPROVED`）
3. **Post 批次扩展**：选中行含 `bank_process_posted_id` 时，扩展为同 posted id 全部 WIN/LOSE 行（一次 Post 的多条流水一并归档删除）
4. **`INSERT … SELECT` → `transactions_deleted`**（`archiveBankProcessMaintenanceToDeleted`；`deleted_by` = session `login_id`，`deleted_at` = NOW()）
5. **`DELETE FROM bank_process_resend_daily_guard`**（受影响 `bank_process_id`；解除同日 Resend 锁）
6. **`DELETE FROM transactions`**（`deleteByIdsAndTenantId`）
7. 任一步失败整段回滚

不是主表加 `deleted_at` 的原地软删；主表物理删除以保证 Transaction Search / History / 余额不易误算。列表靠 §3 合并 `transactions_deleted` 展示划线行。

#### 与 Payment Maintenance 差异

| 项 | Payment | Bank Process |
|----|---------|--------------|
| 过滤 | `bank_process_posted_id IS NULL` | `bank_process_posted_id IS NOT NULL` |
| 类型 | PAYMENT / CLAIM / … / RATE | WIN / LOSE |
| 批次扩展 | `rate_group_id` | `bank_process_posted_id` |
| 额外清理 | `transactions_rate` | `bank_process_resend_daily_guard` |

### 4.2 端点

`POST /api/maintenance/bankprocess-maintenance/delete`

### 4.3 请求（`BankProcessMaintenanceDeleteRequest`）

```json
{
  "tenantId": 12,
  "transactionIds": [101, 102, 103]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | `> 0` |
| `transactionIds` | 是 | 活数据 `transactions.id`；前端去重且仅正整数 |

`deleted_by` **不由前端传**，取自 session `login_id`。

### 4.4 响应

成功：

```json
{
  "success": true,
  "message": "BankProcess deleted successfully",
  "data": null
}
```

失败：`success: false`，`message` 为业务错误文案，例如：

- `Not logged in`
- `Read-only access cannot delete transactions`
- `Invalid tenant id`
- `Please select at least one record`
- `No matching bank process maintenance records to delete`
- `Failed to archive / delete bank process maintenance records`

### 4.5 前端 Delete

- `buildSpringBankprocessMaintenanceDeleteRequest({ tenantId, transactionIds })`
- `deleteBankprocessData` → `POST api/maintenance/bankprocess-maintenance/delete`
- 页面用 `companyId` 作为 `tenantId`
- 已删行：`isBankprocessMaintenanceRowSelectable` 返回 false（不可再勾选）
- 删除后重新 list；Deleter 列显示 `{deletedBy} ({deletedAt})`，整行红色划线

---

## 5. 关键文件

| 层 | 路径 |
|----|------|
| Controller | `MaintenanceController.java` |
| Service | `MaintenanceService.java`，`MaintenanceServiceImpl.java` |
| DTO | `TransactionDTO.BankProcessMaintenanceRequest` / `DeleteRequest` / `BankProcessMaintenanceRow` |
| Dao / Mapper | `TransactionDao.java`，`TransactionMapper.xml`；`BankProcessResendDao.java`（guard 清理） |
| Frontend | `bankprocessMaintenanceLogic.js`，`BankprocessMaintenancePage.jsx`，`BankprocessVirtualDataRow.jsx` |

---

## 6. 变更检查清单

- [ ] List 是否仅 WIN/LOSE 且 `bank_process_posted_id IS NOT NULL`
- [ ] 是否 merge 归档行（软删划线 + Deleter 列）
- [ ] From 列是否在无 from account 时回退 `card_owner`
- [ ] 前端 batch 勾选是否仍用 `bankProcessId` + `periodType` + `transactionDate`
- [ ] Delete 是否扩展同 `bank_process_posted_id` 全部行
- [ ] Delete 是否清理 `bank_process_resend_daily_guard`
- [ ] 与 Payment 文档交叉引用是否一致
