# Spring Boot Schema 迁移对照说明

> **目标 schema**：`schema.sql`（本目录）  
> **对照来源**：旧 PHP 库（`count168.org` / `easycount_schema.sql`、`games_schema.sql`；以及 `backend/src/main/resources/schema.sql` 残缺摘录）  
> **运行库示例**：`testcount`  
> **最后更新**：2026-08-27

本文说明旧表在新租户模型（`tenant`）下如何 **迁移 / 拆分 / 合并 / 优化 / 弃用**。  
**只谈表结构与设计意图**；业务 API 是否已切到 Spring 另见 `docs/frontend-springboot-migration.md` 第32节（Data Capture）。

---

## 1. 总原则

| 原则 | 说明 |
|------|------|
| 统一租户 | 废弃 `company` / `groups` / `scope_type`+`scope_id`，一律用 `tenant.id` |
| 去掉 JSON 配置列 | 如 `description_ids`、`schedule_days`、`fee_share_allocations`、`profit_sharing` → 关联表 |
| ENUM 大写 | 新表状态/类型多用 `ACTIVE`/`INACTIVE`、`MAIN`/`SUB`、`GAME`/`BANK` |
| 审计用 login_id | `created_by` / `updated_by` 存字符串 login，不强制存 user 数字 id |
| 备份表不进新库 | 所有 `*_backup`、多数 `*_deleted` 归档表不迁移 |
| 配置 vs 快照分离 | Data Capture：**公式配置**与**提交行快照**分表 |

---

## 2. 一览：旧 → 新

### 2.1 身份 / 租户 / 权限

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `owner` | `owner` | 保留并整理 |
| `user` | `user` | 保留并整理（角色走 `user_role`） |
| `account` | `account` | 保留并整理；`account_id` **按 tenant 唯一**（经 `account_tenant_access`），非全局唯一 |
| `company` | `tenant`（`tenant_type=COMPANY`） | 迁移合并 |
| groups（逻辑） | `tenant`（`tenant_type=GROUP`） | 迁移合并 |
| `user_company_map` | `user_tenant_access` | 迁移 |
| `user_company_permissions` | `user_tenant_account_access` + `user_tenant_process_access` | 拆分规范化 |
| `account_company` | `account_tenant_access` | 迁移 |
| `role` | `user_role` + `permission` + `user_role_permission` | 拆分 |
| （无清晰等价） | `feature_module` + `tenant_feature_module` | 新增（Games/Bank 等模块开关） |
| （group 互链） | `tenant_link` | 新增 |
| `password_reset_tac` | **弃用**；密码重置改走 Redis TAC | 弃用（原表已从 schema.sql 移除） |
| `password_reset_tac_owner` | **弃用**；同上 | 弃用（原表已从 schema.sql 移除） |

> **注**：`testcount` 库当前实际表名仍是 `tenant_auto_renew_request` / `tenant_auto_renew_request_transaction`（早期建库时的命名，`AutoRenewMapper.xml`、`migrate_auto_renew_delete.sql` 也用这个名字），已存在的库不重命名。`schema.sql` 里"从零建库"用的新表名是 `tenant_auto_renew` / `tenant_auto_renew_transaction`（不含 `request` 字样）——**两者目前不一致，全新建库时以 schema.sql 为准，但 mapper/增量脚本尚未同步改名**，谁先动手改代码那边请一并同步。

### 2.2 Domain / 公告 / 币别 / Ownership

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `domain_list_fee_settings`（含 JSON 价列） | `domain_list_fee_price` + `renewal_period` | 规范化 |
| `announcements` | `announcements`（对齐 tenant / C168） | 保留整理 |
| `maintenance_marquee` | `maintenance_marquee` | 保留整理 |
| `currency`（`company_id`+`scope_*`） | `currency`（仅 `tenant_id`） | 优化 |
| `account_currency` | `account_currency` | 保留 |
| `account_currency_display_order` | **弃用**；顺序并入 `account_currency.sort_order` | 合并优化 |
| `company_ownership` / `group_ownership` | `tenant_ownership` | 合并迁移 |
| `company_ownership_history` / `group_ownership_history` | `tenant_ownership_history` | 合并迁移 |
| （自动续期申请） | `tenant_auto_renew` + `tenant_auto_renew_transaction` | 新模型表（表名不含 `request` 字样；`testcount` 中因历史原因仍叫 `tenant_auto_renew_request`/`_request_transaction`，不影响，见下方注记） |
| （费用分成 JSON） | `tenant_fee_share_allocation` | 规范化 |
| `account_link` | `account_link` | 保留 |

### 2.3 Process

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `process`（含 JSON：`description_ids`、`schedule_days` 等） | `process`（无 JSON；`category`=`GAME`/`BANK`） | 优化 |
| `description` | `process_description` | 重命名迁移 |
| （process↔description） | `process_description_link` | 新增桥表 |
| `day`（星期名字典） | **弃用**；`process_day.day_of_week`（1=Mon…7=Sun） | 内联优化 |
| `process_day` | `process_day` | 保留整理 |
| `submitted_processes` | `process_submitted` | 重命名迁移 |

### 2.4 Data Capture

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `data_captures`（`company_id`+`scope_*`） | `data_captures`（`tenant_id`+`category`） | 迁移优化 |
| （GAME 多选 description） | `data_capture_description` | 新增桥表 |
| `data_capture_templates` | `data_capture_formula` | 迁移优化（持久配置，不绑单次 capture） |
| `data_capture_details` | `data_capture_line` | 迁移优化（Submit 行快照，绑 `capture_id`） |
| `data_capture_group_draft`（`draft_json`） | `data_capture_draft` + `data_capture_draft_cell` | 拆分去 JSON |
| `data_capture_submit_queue` | **不建**（见 §4） | 弃用 |
| `data_capture_summary_state` | **不建**（见 §4） | 弃用 |
| `data_captures_deleted` / `*_backup` | **不建** | 弃用 |

### 2.5 Bank Process

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `country_bank` / `company_countries` / `company_selected_*` | `bank_country` + `bank_option` | 规范化 |
| `bank_process`（含 `profit_sharing` TEXT 等） | `bank_process` + `bank_process_share` | 拆分优化 |
| `process_accounting_posted`（旧命名混用） | `bank_process_accounting_posted` | 重命名迁移 |
| `bank_process_accounting_resend_daily_guard` | `bank_process_resend_daily_guard` | 重命名整理 |
| `bank_process_maintenance_resend_pending` | **弃用**；开放 Resend 排程写入 `bank_process.resend_schedule_*` 列 | 合并优化 |

### 2.6 Transactions / RATE

| 旧表 | 新表 / 做法 | 类型 |
|------|-------------|------|
| `transactions`（`company_id` 等） | `transactions`（`tenant_id`；类型含 `PROFIT`/`ADJUSTMENT`…） | 迁移优化 |
| `transactions_rate` | `transactions_rate`（RATE 组头：汇率 + 两腿 txn 链接） | 保留优化 |
| `transactions_rate_details` | **弃用**（信息收进 `transactions` 腿 + `transactions_rate`） | 优化删除 |
| `transaction_entry` | **弃用**（同上，不再维护第三套分录） | 优化删除 |
| `transactions_deleted` | `transactions_deleted`（`company_id`→`tenant_id`） | 迁移整理 |

### 2.7 未迁入新 schema（可选 / 未定）

| 旧表 | 说明 |
|------|------|
| `auto_login_credentials` | 自动登录/抓取凭证；新模型未实现则不建 |
| `deleted_logs` | 通用删改审计 JSON；Payment 等已有 `transactions_deleted`，暂不迁 |
| `fx_daily_rates` | 2026-08-27 备份中首次出现的每日汇率抓取记录表（`base_code`/`quote_code`/`rate_date`/`rate`/`source`）；新库暂无对应表，是否需要迁入待定 |

---

## 3. 分模块详解

### 3.1 租户统一（`company` / groups → `tenant`）

**旧问题**：公司与集团两套表、权限 map 叠床架屋，Data Capture / Transaction 再叠 `scope_type`+`scope_id`。

**新模型**：

- `tenant`：同一 ID 空间，用 `tenant_type` 区分 GROUP / COMPANY  
- `tenant_link`：集团互链（如 AP+IG）  
- `tenant_feature_module`：开通 Games / Bank 等  
- 业务表一律 `tenant_id`，**不再**出现 `company_id` + `scope_*`

### 3.2 权限与 ACL

| 旧 | 新 |
|----|----|
| `role` 单表或松散字符串 | `user_role` 字典 + `permission` 字典 + `user_role_permission` 默认角色权限 |
| `user_company_map` | `user_tenant_access` |
| 账户/流程权限塞 JSON 或杂表 | `user_tenant_account_access`、`user_tenant_process_access` |
| `account_company` | `account_tenant_access` |

Sidebar 模块 key 与 `permission` / 登录 session 边界转换见后端 Permission 实现与前端 `canAccess`。

### 3.3 Currency

| 旧 | 新 |
|----|----|
| `currency` 带 `company_id` / `scope_type` / `scope_id` | 仅 `tenant_id` |
| 单独 `account_currency_display_order` | 删除；`account_currency.sort_order` |

相关 migrate：`migrate_rate_tables_optimized.sql`（RATE）、币别整理历史见 currency 相关讨论与 schema 注释。

### 3.4 Process：去 JSON

旧 `process` 常把 description 列表、星期塞进 JSON，难校验、难 JOIN。

新结构：

```
process
  ├── process_description_link → process_description
  └── process_day (day_of_week 1–7)
```

- `description` → `process_description`  
- 字典表 `day` **不再需要**  
- GAME 当日已提交过滤：`process_submitted`（替代 `submitted_processes`）

### 3.5 Data Capture：配置 / 草稿 / 快照

```
公式配置（可 Maintenance 硬删）     单次提交头              提交行快照
data_capture_formula          data_captures ──┬── data_capture_line
                                              └── data_capture_description (GAME)

BANK 表格草稿（非 PROFIT）
data_capture_draft ── data_capture_draft_cell
```

#### `data_capture_templates` → `data_capture_formula`

- 持久公式，**不**绑定某次 `data_captures.id`  
- 不存 `last_processed_amount` / `data_capture_id` 等提交态字段  
- `product_type`：`MAIN` / `SUB`（大写）  
- 迁移脚本：`migrate_datacapture_formula.sql`

#### `data_capture_details` → `data_capture_line`

- Summary **最终 Submit** 的行级快照，FK → `data_captures.id`  
- `tenant_id` 替代 `company_id`+`scope_*`  
- `account_id` 改为 INT FK（旧常为 varchar）  
- `columns_value` → `source_columns`  
- 首版即含 `rate_expression`  
- 供 Customer Report / Transaction History 等读金额  
- 迁移脚本：`migrate_datacapture_line.sql`  
- **注意**：DDL 已就绪；PHP 仍可能写旧 `data_capture_details`，直到 Spring Summary Submit 切换

#### `data_capture_group_draft` → `data_capture_draft` + `_cell`

- 去掉整包 `draft_json`  
- 头表：`UNIQUE(tenant_id, process_id, currency_id)`  
- 单元格：`(row_index, col_index, cell_value)`，空格不落库  
- PROFIT 不写草稿（业务规则，非表约束）  
- 迁移脚本：`migrate_datacapture_draft.sql`

### 3.6 Bank Process

| 优化点 | 说明 |
|--------|------|
| 国家/银行选项 | `bank_country` / `bank_option`，不再散落 company_selected_* |
| 分润行 | `bank_process_share` 替代 `profit_sharing` TEXT |
| Accounting Due 台账 | `bank_process_accounting_posted` |
| 同日 Resend 锁 | `bank_process_resend_daily_guard` |
| 开放补单排程 | 列在 `bank_process` 上（`resend_schedule_*`），替代独立 pending 表 |

Due 行为细则见 `docs/frontend-springboot-migration.md` 第31节。

### 3.7 Transactions / RATE 三表归一

旧 PHP RATE 曾并行维护：

1. `transactions`  
2. `transactions_rate` / `transactions_rate_details`  
3. `transaction_entry`

**新约定**：

- 普通流水：仅 `transactions`（一行一账户金额）  
- RATE：`transactions` 两腿（同 `rate_group_id`）+ **一张** `transactions_rate` 组头（汇率、腿 id、可选 Middle-Man 列）  
- **不建** `transactions_rate_details`、`transaction_entry`  
- 软删归档：`transactions_deleted`

参考：`migrate_rate_tables_optimized.sql`、`rate_tables_optimized_reference.sql`。

### 3.8 2026-07-29 之后的结构性变更（本次补记）

以下改动已落在 `schema.sql`，但截至本次更新前一直没写进本文档：

| 表 | 变更 | 说明 |
|----|------|------|
| `process` | 新增 `copied_from_process_id` | Copy From 功能：记录来源 process.id，仅用于追溯排查，不影响业务逻辑 |
| `data_capture_formula` | 删除 `formula_operators`；新增 `formula_group_id` | `formula` 成为计算与展示唯一来源；`formula_group_id` 是 Copy From 建立的同步分组标签（非外键，同组编辑互相同步，删除不连带） |
| `data_capture_line_deleted`（新表） | Capture Maintenance 软删归档 | 删除单位是**整个 capture**（同 capture_id 下所有行一起删），不支持按行单删；`data_captures` header 永不清理 |
| `process_submitted` | `user_id`(FK user) → `created_by`(VARCHAR login_id)；去掉 `(tenant_id, process_id, capture_date)` 唯一键；新增 `capture_id` FK → `data_captures` | GAME 当日去重改靠 service 层 `existsProcessSubmitted`，不再靠 DB 唯一键；BANK 现在也会写入本表（之前仅 GAME 用于过滤），允许同日多次提交 |
| `bank_process` | 新增 `day_end_monthly_cap_enabled`、`expired_at_creation` | Accounting Due 月结逻辑：区分"每月1号"与"MONTHLY"两种频率下月结上限口径 |
| `transactions_rate` | `middleman_amount` 语义变化（Service Fee 面值，不再做汇率换算）；新增 `middleman_rate_expression`、`platform_fee_amount` | Middle-Man 现分 Rate-Mul（乘/除模式）与 Service Fee 两种；Platform Fee 只冲减 Middle-Man 的 Win/Loss，不单独出账 |
| `tenant_ownership_history` | `saved_by` INT(FK user.id) → VARCHAR(50) | 改存 login_id 字符串（admin=`user.login_id`；owner=`owner_code`），去掉外键约束，对齐 §1 总原则"审计用 login_id" |
| `process_description` | 新增 `UNIQUE(tenant_id, name)` | 防止同租户下重复描述 |
| `v_company_tenant` / `v_group_tenant`（新视图） | 按 `tenant_type` 拆分 `tenant` 的只读视图 | 供报表/查询按公司或集团单独取数 |

---

## 4. 明确弃用、新库不创建

| 旧表 | 弃用原因 |
|------|----------|
| `data_capture_submit_queue` | 仅为 PHP `post_max_size` / 分批 Submit / 旧 immediateAck 路径服务。Spring 可单次事务写入 `data_captures` + `data_capture_line`。前端亦已要求等真实写库成功。 |
| `data_capture_summary_state` | 仅存 Summary UI `state_json`。公式 → `data_capture_formula`；入账行 → `data_capture_line`；BANK 草稿 → `data_capture_draft*`；未提交 UI 状态用前端 session/localStorage。 |
| `transaction_entry` | RATE 优化后冗余分录表 |
| `transactions_rate_details` | RATE 优化后冗余明细表 |
| `account_currency_display_order` | 合并进 `account_currency.sort_order` |
| `day` | 星期用 `process_day.day_of_week` 数字即可 |
| `bank_process_maintenance_resend_pending` | 排程字段并入 `bank_process` |
| `data_captures_deleted` 及各类 `*_backup` | 不进 Spring 目标库；运维备份另案 |
| `company` / `account_company` / `user_company_map` 等 | 由 `tenant` + `*_tenant_access` 体系替代（见 §2） |

---

## 5. 新模型新增、旧库无同名表

下列主要是租户化 / 规范化后**新出现**的表（旧侧无 1:1 同名）：

- `tenant`、`tenant_link`、`tenant_feature_module`、`tenant_fee_share_allocation`  
- `feature_module`、`permission`、`user_role`、`user_role_permission`  
- `user_tenant_access`、`user_tenant_account_access`、`user_tenant_process_access`、`account_tenant_access`  
- `process_description_link`、`data_capture_description`  
- `data_capture_formula`、`data_capture_line`、`data_capture_draft`、`data_capture_draft_cell`  
- `bank_country`、`bank_option`、`bank_process_share`  
- `domain_list_fee_price`（相对旧 settings JSON）  
- `tenant_ownership` / `tenant_ownership_history` / `tenant_auto_renew` / `tenant_auto_renew_transaction`  
- `data_capture_line_deleted`（Capture Maintenance 软删归档；按整个 capture 归档，不支持单行删）  
- `v_company_tenant` / `v_group_tenant`（`tenant` 按 `tenant_type` 拆分的只读视图）  

（部分在旧库有「功能等价」表，但名称与形状已变，见 §2。）

---

## 6. 本目录相关脚本索引

| 文件 | 用途 |
|------|------|
| `schema.sql` | 新库全量 DDL（含 DROP 顺序） |
| `migrate_datacapture_formula.sql` | 增量加 `data_capture_formula` |
| `migrate_datacapture_line.sql` | 增量加 `data_capture_line` |
| `migrate_datacapture_draft.sql` | 增量加 BANK draft 表 |
| `migrate_process_category.sql` | process 分类 / GAME·BANK |
| `migrate_rate_tables_optimized.sql` | RATE 表优化 |
| `rate_tables_optimized_reference.sql` | RATE 优化参考说明 |
| `migrate_account_id_unique_per_tenant.sql` | account_id 唯一性按 tenant |
| `migrate_enums_to_uppercase.sql` | 枚举大写 |
| `migrate_auto_renew_delete.sql` | 增量加 auto renew 关联流水表；**注意**：用的是旧名 `tenant_auto_renew_request_transaction`，与 `schema.sql` 里全新建库用的 `tenant_auto_renew_transaction` 不一致（见 §2.1 注） |
| `migrate_role_hierarchy_and_admin_permission_fix.sql` | 修正 `user_role.hierarchy_level`（PARTNERSHIP 从 8 改为 2，紧排在 OWNER 之后）；移除 `CUSTOMER_SERVICE` 的 `ADMIN`（员工列表）侧边栏权限（如存在） |
| 其他 `migrate_*` / `add_*` / `seed_*` | 各子域增量与种子数据 |

应用示例：

```powershell
Get-Content backend\src\main\resources\sql\migrate_datacapture_line.sql -Raw |
  C:\xampp\mysql\bin\mysql.exe -u root
```

---

## 7. Schema 完成度（截至 2026-08-27）

| 状态 | 内容 |
|------|------|
| ✅ 核心业务表 | Login、权限、Domain、Ownership、Currency、Process（含 Copy From）、Bank Process、Transactions/RATE（含 Platform Fee）、Data Capture（含 formula / line / line_deleted / draft）DDL 已就绪 |
| ✅ 故意不建 | `submit_queue`、`summary_state`、RATE 旧明细/分录、`password_reset_tac*`、backup 表等（§4） |
| ⚪ 可选未建 | `auto_login_credentials`、`deleted_logs`、`fx_daily_rates`（旧库 8/27 备份新出现，待定） |
| ⚠️ 非 schema 缺口 | 部分业务仍走 PHP 旧表（如 Summary Submit 仍可能写 `data_capture_details`）。属 **API 迁移**，不是缺 DDL |
| ⚠️ 命名不一致（待修） | `tenant_auto_renew*`：`schema.sql` 用不含 `request` 的新名，`testcount` 实际库 + `AutoRenewMapper.xml` + `migrate_auto_renew_delete.sql` 仍用旧名 `tenant_auto_renew_request*`（见 §2.1 注） |

---

## 8. 维护约定

1. 增删改目标表时：**同步改 `schema.sql`**，需要增量时再补 `migrate_*.sql`。  
2. 若某旧表决定「永不迁入」，在本文 **§4** 补一行原因，并在 `schema.sql` 注释写 `NOT planned`。  
3. Data Capture 行为与前端契约：优先更新 `docs/frontend-springboot-migration.md` 第32节。  
4. Accounting Due / Resend：优先更新 `docs/frontend-springboot-migration.md` 第31节。  
5. 勿把 `backend/src/main/resources/schema.sql`（旧摘录）当作完整旧库或新目标结构。
