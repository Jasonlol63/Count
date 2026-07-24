# Payment Maintenance — List / Delete（Spring）

Payment Maintenance 页面的列表与软删除约定。修改 API、过滤规则、`transactions_deleted` 表结构或前端契约时，**同步更新本文档**。

Maintenance 侧边栏（含 Bank Process 入口）：[`maintenance-navigation.md`](./maintenance-navigation.md)

## 1. 范围与原则

| 项 | 约定 |
|----|------|
| 页面 | Count-frontend `pages/maintenance/payment/*` |
| 数据源 | `transactions`（活数据）+ `transactions_deleted`（软删归档） |
| 租户 | **一律 `tenantId`**（公司 pill 的 numeric id = tenant.id） |
| 不做 | 不再传 / 校验 `company_id`、`group_id`、`view_group`、`report_scope`、`group_aggregate` 等 scope 参数 |
| 不含 | Bank Process 入账行（`bank_process_posted_id IS NOT NULL`） |
| 含 | 手动交易 + Domain / Renew 相关流水（类型见下；Domain/Renew 一般为 `PAYMENT`） |
| 契约方向 | **前端对齐后端**（camelCase JSON / `tenantId`），不为兼容旧 PHP 再造字段 |

Payment 与 Bank Process Maintenance **共用** `transactions_deleted`；Formula Maintenance 为硬删，**不**进本表。

Bank Process Maintenance 列表/删除：[`bankprocess-maintenance-list-delete.md`](./bankprocess-maintenance-list-delete.md)

## 2. 允许的 `transaction_type`

```text
PAYMENT, CLAIM, CLEAR, CONTRA, RATE, ADJUSTMENT, PROFIT
```

- **已移除遗留类型 `RECEIVE`**（entity / schema / 前端筛选与提交守卫均已去掉）。
- **不含** `WIN` / `LOSE`（Bank Process Win/Loss，走 Bank Process Maintenance）。

Service 层：`MaintenanceServiceImpl.ALLOWED_TYPES` 与 Mapper fragment `paymentMaintenanceTransactionTypes` 保持一致。

## 3. 数据库

### 3.1 `transactions_deleted`（优化后）

归档表：软删时先拷贝再从 `transactions` 物理删除。列表仍可展示划线行 + Deleter。

定义见：

- [`backend/src/main/resources/sql/schema.sql`](../backend/src/main/resources/sql/schema.sql)
- [`backend/src/main/resources/schema.sql`](../backend/src/main/resources/schema.sql)

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | INT PK AI | 归档行主键 |
| `transaction_id` | INT NOT NULL | 原 `transactions.id` |
| `tenant_id` | INT NOT NULL | 租户（取代旧 `company_id`） |
| `transaction_type` | ENUM(...) | 与主表一致（无 `RECEIVE`） |
| `account_id` | INT NOT NULL | To Account |
| `from_account_id` | INT NULL | From Account |
| `currency_id` | INT NULL | 币种 |
| `amount` | DECIMAL(25,8) | 金额 |
| `transaction_date` | DATE | 交易日（列表日期筛选用此字段） |
| `description` | VARCHAR(500) | 描述 |
| `remark` | VARCHAR(500) | 备注（取代旧 `sms`） |
| `created_by` | VARCHAR(100) | 原提交人 **login_id** |
| `created_at` | TIMESTAMP | 原创建时间（列表 Created At） |
| `deleted_by` | VARCHAR(100) | 删除人 **login_id**（UI Deleter） |
| `deleted_at` | TIMESTAMP | 删除时间 |
| `bank_process_posted_id` | INT NULL | `NULL` = Payment Maintenance；有值 = BP Maintenance 归档 |
| `rate_group_id` | VARCHAR(50) NULL | RATE 组（如有） |

索引：

- `(tenant_id, transaction_date)`
- `(transaction_id)`
- `(deleted_at)`
- `(tenant_id, bank_process_posted_id)`

Entity：[`TransactionDeleted.java`](../backend/src/main/java/com/eazycount/entity/TransactionDeleted.java)

### 3.2 相对旧 PHP 表的变更

| 旧列 / 概念 | 新约定 |
|-------------|--------|
| `company_id` | → `tenant_id` |
| `scope_type` / `scope_id` | **删除**（API 不再使用 scope） |
| `sms` | → `remark` |
| `created_by` / `created_by_owner`（数字 user/owner id） | → `created_by`（login_id 字符串） |
| `deleted_by_user_id` / `deleted_by_owner_id` | → `deleted_by`（login_id） |
| `source_bank_process_id` | → `bank_process_posted_id`（对齐 Spring 主表） |
| `source_bank_process_period_type` | **删除**（当前归档不存） |
| enum 含 `RECEIVE` | **去掉 RECEIVE**；补齐 `CLAIM` / `CLEAR` / `PROFIT` 等 |

### 3.3 相关迁移脚本

| 文件 | 用途 |
|------|------|
| `sql/migrate_transaction_type_add_profit.sql` | 主表 type 增加 `PROFIT`（enum 已无 RECEIVE） |
| `sql/migrate_transaction_type_drop_receive.sql` | 已上线库从 enum 去掉 `RECEIVE`（执行前确认无 `RECEIVE` 行） |
| `sql/migrate_transactions_deleted_created_by_login_id.sql` | 归档表 `created_by` / `deleted_by` 改为 VARCHAR login_id（旧库 INT 会导致 archive 失败） |

已有旧库升级到新 `transactions_deleted` 时，需单独做数据迁移（`company_id`→`tenant_id`、`sms`→`remark`、deleter 字段合并等），不能只改 schema 定义。

## 4. 架构分层

```text
PaymentMaintenancePage (UI: Group/Company pills)
  → resolvePaymentMaintenanceTenantId()  // 仅得到 tenantId
  → POST /api/maintenance/payment-maintenance/list|delete

MaintenanceController
  → MaintenanceService / MaintenanceServiceImpl
      → TransactionDao + TransactionMapper.xml
          → transactions / transactions_deleted
```

- **SQL / Dao**：留在 Transaction 模块（数据归属 `transactions`）。
- **Controller / Service**：挂在 Maintenance 用例门面（`/api/maintenance/...`）。
- **不**把交易 SQL 塞进与跑马灯同名的旧 Maintenance 实体。

## 5. List API

### 5.1 端点

`POST /api/maintenance/payment-maintenance/list`

### 5.2 请求（`PaymentMaintenanceRequest`）

```json
{
  "tenantId": 12,
  "dateFrom": "24/07/2026",
  "dateTo": "24/07/2026",
  "transactionType": "PAYMENT",
  "currencyCodes": ["MYR"],
  "q": "ABC"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | `> 0` |
| `dateFrom` / `dateTo` | 是 | `dd/MM/yyyy` 或 `yyyy-MM-dd`；闭区间；`dateTo >= dateFrom` |
| `transactionType` | 否 | 空 / null = 全部允许类型；非法值抛错 |
| `currencyCodes` | 否 | 空数组 = 全部币种；元素会规范化为大写 |
| `q` | 否 | 模糊匹配 To/From account code、description、remark、createdBy |

日期过滤字段：`transaction_date`（不是 `created_at`）。  
展示列 Created At 仍来自 `created_at`。

### 5.3 列表 SQL 过滤（活数据）

Dao：`findPaymentMaintenanceRows`

- `tenant_id = #{tenantId}`
- `bank_process_posted_id IS NULL`
- `approval_status = 'APPROVED'`
- `transaction_type IN (paymentMaintenanceTransactionTypes)`
- `transaction_date` 闭区间 + 可选 type / currency / `q`
- `ORDER BY created_at DESC, id DESC`

### 5.4 响应行（`PaymentMaintenanceRow`）

| JSON 字段 | 含义 |
|-----------|------|
| `id` | 活数据为 `transactions.id`；归档行为原 `transaction_id` |
| `transactionType` | 类型 |
| `createdAt` | Created At |
| `toAccountCode` / `fromAccountCode` | Account(To) / Account(From) |
| `amount` / `currencyCode` | 金额与币种 |
| `description` / `remark` | 描述 / 备注 |
| `createdBy` | Submitter（login_id） |
| `deleted` | `false` 活数据；`true` 归档 |
| `deletedBy` / `deletedAt` | Deleter；活数据为 null |

Envelope：

```json
{
  "success": true,
  "message": "Payment maintenance list retrieved",
  "data": [ /* PaymentMaintenanceRow[] */ ]
}
```

### 5.5 前端 List

- 文件：`Count-frontend/src/pages/maintenance/payment/paymentMaintenanceLogic.js`
- `buildSpringPaymentMaintenanceRequest` → 只组后端字段
- `normalizeSpringPaymentMaintenanceRow` → 表格用 `dts_created` / `account` / `from_account` / `is_deleted` / `deleted_by` / `dts_deleted` 等
- 币种：`POST /api/currency/list?tenant_id=`
- 公司列表：`fetchOwnerCompaniesAll`（Spring tenant-accessible）

### 5.6 List 合并归档行（软删 UI）

`MaintenanceServiceImpl.findPaymentMaintenanceRows` 并行查 live + archived，合并后按 `createdAt` / `id` 降序：

1. `findPaymentMaintenanceRows` — 活数据（`deleted=false`）
2. `findPaymentMaintenanceDeletedRows` — `transactions_deleted`（`deleted=true`，含 `deletedBy` / `deletedAt`）

删除后行仍出现在列表：前端 `maintenance-row-deleted` 划线样式，Deleter 列显示 `{deletedBy} ({deletedAt})`，已删行不可再勾选（`isPaymentMaintenanceRowSelectable` → false）。

## 6. Delete API（软删）

### 6.1 行为（与旧 PHP 一致的语义）

`MaintenanceServiceImpl.deletePaymentMaintenanceRows`（`@Transactional`）：

1. 校验登录、非只读、`tenantId`、`transactionIds`
2. `resolveDeletableBatch`：加载选中行 → 过滤可删（`bank_process_posted_id IS NULL` 且 type ∈ 允许列表）
3. **RATE 扩展**：选中行含 `rate_group_id` 时，扩展为同组全部 leg（含 Middle-Man fee）；收集 `rateGroupIds`
4. **`INSERT … SELECT` → `transactions_deleted`**（`archivePaymentMaintenanceToDeleted`；`deleted_by` = session `login_id`，`deleted_at` = NOW()）
5. **`DELETE FROM transactions_rate`**（若有 `rateGroupIds`；解除 `leg1_transaction_id` / `leg2_transaction_id` FK）
6. **`DELETE FROM transactions`**（`deleteByIdsAndTenantId`）
7. 任一步失败整段回滚

不是主表加 `deleted_at` 的原地软删；主表物理删除以保证 Transaction Search / History / 余额不易误算。列表靠 §5.6 合并 `transactions_deleted` 展示划线行。

#### RATE 删除注意

| 项 | 说明 |
|----|------|
| FK | `transactions_rate.leg1/leg2_transaction_id` → `transactions.id`；必须先删 rate header |
| 扩展 | 删一条 RATE leg 会 archive + 删除整组同 `rate_group_id` 行 |
| Dao | `findPaymentMaintenanceIdsByRateGroupIds`、`TransactionRateDao.deleteByTenantIdAndRateGroupIds` |

### 6.2 端点

`POST /api/maintenance/payment-maintenance/delete`

### 6.3 请求（`PaymentMaintenanceDeleteRequest`）

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

### 6.4 响应

成功（当前 Controller）：

```json
{
  "success": true,
  "message": "Process deleted successfully",
  "data": null
}
```

Service 内部有 `PaymentMaintenanceDeleteResult.deleted`（实际删除条数）；若需返回给前端，可在 Controller 把 `data` 设为该 result。

失败：`success: false`，`message` 为业务错误文案，例如：

- `Not logged in`
- `Read-only access cannot delete transactions`
- `Invalid tenant id`
- `Please select at least one record`
- `No matching payment maintenance records to delete`
- `Failed to archive / delete payment maintenance records`

### 6.5 前端 Delete

- `buildSpringPaymentMaintenanceDeleteRequest({ tenantId, transactionIds })`
- `deletePaymentRecords` → `POST api/maintenance/payment-maintenance/delete`
- 页面用 `activeTenantId`（`resolvePaymentMaintenanceTenantId`）
- 已删行：`isPaymentMaintenanceRowSelectable` 返回 false（不可再勾选）
- 成功后清空选中并重新 list

## 7. 关键文件索引

| 层 | 路径 |
|----|------|
| Controller | `backend/.../controller/MaintenanceController.java` |
| Service | `backend/.../service/MaintenanceService.java` |
| ServiceImpl | `backend/.../service/impl/MaintenanceServiceImpl.java` |
| DTO | `TransactionDTO.PaymentMaintenanceRequest` / `DeleteRequest` / `DeleteResult` / `PaymentMaintenanceRow` |
| Entity | `TransactionDeleted.java` |
| Dao / Mapper | `TransactionDao.java`，`TransactionMapper.xml`（含 archive / delete / list / deleted list） |
| Schema | `resources/sql/schema.sql`，`resources/schema.sql` |
| Frontend | `Count-frontend/.../payment/paymentMaintenanceLogic.js`，`PaymentMaintenancePage.jsx` |

## 8. 变更时检查清单

- [ ] 允许类型是否前后端 + Mapper fragment 三处一致  
- [ ] List / Delete 是否仍只使用 `tenantId`（无 scope 回归）  
- [ ] `transactions_deleted` 列是否与 `TransactionDeleted` entity / INSERT SELECT 一致  
- [ ] 去掉或新增 type 时是否更新 enum 迁移脚本  
- [ ] List 若需软删划线展示：Service 是否已 merge live + `findPaymentMaintenanceDeletedRows`（已实现）
- [ ] Delete RATE 行：是否扩展 rate group + 先删 `transactions_rate`（已实现）
- [ ] 前端归一化是否覆盖 `deleted` / `deletedBy` / `deletedAt`
- [ ] Bank Process Maintenance 侧边栏：是否用 `tenant_has_bank`（见 `maintenance-navigation.md`）
