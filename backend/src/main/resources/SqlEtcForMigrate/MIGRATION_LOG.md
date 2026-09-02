# 旧库 → Spring Boot 新库 数据迁移记录

> 源库：`c168_net_legacy_20260827`（2026-08-27 c168.net 备份，原样导入的 staging 库，未改动）
> 目标库：`count_real`（全新建库，跑过 `schema.sql`，身份域起始为空）
> 本文档记录：已经跑通的域、发现的数据问题、做过的取舍决定、还没做的部分。
> 脚本本身的假设/取舍写在各 `.sql` 文件顶部注释里，本文档是给人看的执行摘要，细节以脚本注释为准。
> Transactions / Bank Process 两个域"哪些数据没迁、为什么"的速查清单单独整理在 [`SKIPPED_DATA_TRANSACTIONS_BANKPROCESS.md`](SKIPPED_DATA_TRANSACTIONS_BANKPROCESS.md)，本文档 §12/§13 是完整叙述，那份文档是表格速查版。

---

## 1. 总体流程

1. 把最新的 mysqldump 导入一个新建的 staging 库（不动 `easycount`，那是开发用的旧库副本）
2. 对着 staging 库抽样核对，确认字段含义、`scope_type`/`scope_id` 这类字段是否真的有用、有没有脏数据
3. 写一个 `INSERT INTO ... SELECT FROM 旧库.表` 的纯 SQL 脚本（不 hardcode 数据），跑到目标库
4. 跑完对行数、关键字段抽查，核对不上就回去查旧数据，不是无脑信任脚本产出

目标库选择：一开始想用 `testcount`，但它已经有开发阶段手工建的测试数据，`tenant.code`（`AP`、`C168`）、`user.login_id`（`JS`、`MS`）刚好和旧库真实业务码撞车，没法安全地追加迁移数据进去。最终改用一个全新建的空库 `count_real`。

---

## 2. 身份 / 租户域（`migrate_data_identity_tenant_from_legacy.sql`）

### 覆盖范围
`owner`、`tenant`（`company`+`groups` 合并）、`account`、`account_tenant_access`、`user`、`user_tenant_access`。

### 关键发现 / 处理方式

**1. `user.login_id` 不是全局唯一的**
旧库里同一个 `login_id`（比如 `9`、`APPLE`、`JS`）对应好几条独立的 `user` 记录，各自密码、邮箱、创建时间完全不同——验证过（比对密码 hash / email）确认是真实互相独立的账号，只是共用了同一个登录名。新库 `user.login_id` 有全局唯一约束，直接搬会撞键。

处理：脚本里用 `ROW_NUMBER() OVER (PARTITION BY login_id ORDER BY id)` 自动消歧——同名的按 `id`（创建顺序）从小到大，第一个保留原名，后面依次加 `_1`、`_2`...。这个逻辑是**非破坏性的**：只读旧库原始 `login_id` 现算，不改 staging 库本身，每次重新跑都会得到一致的结果。

去重后确认全表 95 条记录、95 个不同 `login_id`，无重复。

**2. `user.role_id` 是全局的，不是按 tenant 分开存的**
新 `user_tenant_access` 表只管"能不能进这个 tenant"（`account_acl_mode`/`process_acl_mode`），不管"在这个 tenant 里是什么角色"——角色只在 `user.role_id` 这一个字段上，一条 `user` 记录只有一个角色，不会因为登的是哪个 tenant 就变。

一开始误以为 APPLE/9 是"同一个人在不同公司角色不同"，需要往 `user_tenant_access` 加角色列才能支持——后来查密码/邮箱证实这是误判，APPLE/9 下面那几条本来就是不同的人（见上一条），不存在"一人多角色"的需求，不需要改 schema。

**3. 权限模型：不迁 per-user 覆盖**
旧 `user.permissions`（JSON，侧边栏模块清单）在同一个角色内因人而异（比如 42 个 admin 里有 8 个是空权限），是真实的 per-user 覆盖，不是角色能推出来的。

最初以为新 schema 没地方放这个、是个缺口——后来核实：这就是产品有意做的简化（[`docs/admin-permission-rbac-hierarchy.md`](../../../../docs/admin-permission-rbac-hierarchy.md)、[`docs/admin-permission-account-override.md`](../../../../docs/admin-permission-account-override.md)），新系统统一走角色默认权限（`user_role_permission`），2026-08-27 那次改动另外加了 `user_permission_override` 支持账号级覆盖，但那是**面向未来新建/编辑账号**用的，不是用来接旧数据的。**决定：旧库 `user.permissions` 这批个性化设置不迁**，迁移后所有账号统一按角色默认权限，`permission_mode` 留默认值 `ROLE_DEFAULT`。

**4. `company`/`groups` 合并进 `tenant` 需要重新分配 ID**
`company.id` 和 `groups.id` 是两套独立的自增序列，合并进同一张 `tenant` 表后 ID 必然重新生成。脚本里用一张 session 级临时表 `_map_tenant(old_type, old_id, new_tenant_id)`，通过业务码（`company.company_id`/`groups.group_code` ↔ `tenant.code`）建立映射，后续所有引用旧 `company_id`/`group_id` 的表都通过这张表转换。

`tenant.parent_id`（company 所属 group）通过 `company.group_id`（varchar，存的是 group_code）反查对应 GROUP tenant 回填。

**5. `owner`/`account`/`user` 的 ID 直接保留旧库原始数字 ID**
因为目标库（`count_real`）身份域是空的，没有 ID 冲突风险，所以这三张表没有走映射表那一套，直接 1:1 保留旧 ID，简化了后续所有引用这些 ID 的表的处理。**这个前提只在"目标库为空"时成立**，如果以后是往已有数据的库里追加，这里要改成映射表方式（同 `tenant` 的做法）。

**6. `account_company`/`user_company_map` 的 `scope_type`（company/group）** 抽样验证：不管 `scope_type` 是 `company` 还是 `group`，`company_id` 列都有实际值且真实可用，直接以 `company_id` 为准即可，`scope_type`/`scope_id` 全程忽略未影响正确性。

### 迁移结果（对照旧库源记录数）

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| owner | 15 | 15 | 一致 |
| tenant | 23 company + 5 group = 28 | 28 | 一致，`parent_id` 回填正确 |
| account | 1483 | 1483 | 一致 |
| account_tenant_access | 1428 | 1428 | 一致 |
| user | 95（去重后） | 95 | 一致 |
| user_tenant_access | 50 | 50 | 一致 |

### 明确没做、留到后面的部分

- **`user_tenant_account_access` / `user_tenant_process_access`**（具体到哪些 account/process 可见）：来源是旧 `user_company_permissions.account_permissions`/`process_permissions` 这两列 JSON，当前用的 MariaDB 10.4 没有 `JSON_TABLE`，没法用纯 SQL 展开成行；`process_acl` 那部分还要等 Process 域迁完、新 `process.id` 都有了才能对上号。**现状**：所有 `user_tenant_access` 行的 `account_acl_mode`/`process_acl_mode` 都是默认值 `ALL`（等同旧库大部分场景下的实际效果，但不是精确复刻旧的 CUSTOM 限制）。

---

## 3. Currency / Domain / Ownership 域（`migrate_data_currency_domain_ownership_from_legacy.sql`）

依赖第 2 节的结果（`tenant`/`owner`/`user`/`account` 已经迁完）。

### 覆盖范围
`currency`、`account_currency`（含 `account_currency_display_order` 折入 `sort_order`）、`domain_list_fee_price`、`announcements`、`maintenance_marquee`、`tenant_ownership`、`tenant_ownership_history`、`tenant_fee_share_allocation`、`account_link`、`tenant_auto_renew`。

### 关键发现 / 处理方式

**1. `currency` 有真实的重复行，需要去重**
同一个 company + 币别代码会出现两条：一条是手动加的（`scope_type='company'`, `sync_source='manual'`），一条是从上级 group 自动同步下来的（`scope_type='group'`, `sync_source='subsidiary'`）。新 schema 每个 `(tenant_id, code)` 只能有一行。

处理：`manual` 优先于 `subsidiary`，用 `ROW_NUMBER()` 挑出存活的一行；另建一张 `_map_currency` 临时映射表，把**所有**旧 `currency.id`（包括被淘汰的那条重复记录）都指向存活的那一条，这样 `account_currency.currency_id` 才不会指到一个没被搬过去的 ID。

去重前 67 行，去重后 55 行（少了 12 行，正好是发现的所有重复对）。`account_currency` 1628 行迁移前后数量不变，说明 remap 没有丢数据。

**2. `account_currency` 没有独立的 tenant 字段**
tenant 归属是通过它引用的 `currency` 行"继承"来的（`currency` 本身按 tenant 隔离），不是 `account_currency` 自己存的，所以这张表的 `tenant_id` 是 JOIN `currency` 得到的，不是直接从旧表搬的。

**3. `domain_list_fee_settings` 只有 1 行，是新旧字段并存**
这张表里 `price`/`maintenance_fee`/`group_price`/`company_price` 这几个"老"字段和 `company_period_prices`/`group_period_prices` 这两个"新"JSON 字段同时存在。新 schema 的 `domain_list_fee_price` 只是 `tenant_type`+`period`+`price` 三元组，判断是后面这两个 JSON 字段（按周期拆分）才是当前实际生效的配置，老的单一 `price` 字段是被取代的旧设计，**决定不迁老字段**，只从两个 JSON 里拆出非空的周期价格（最终只有 3 条：company 的 `6months`/`1year`，group 的 `1year`，其余周期这次备份里都是 `null`）。

**4. `tenant_ownership`/`tenant_ownership_history` 的 `owner_type='account'`**
旧 schema 的枚举定义里有这个值，但实际查下来 0 行使用——新 schema `tenant_ownership.owner_type` 也确实没有 `account` 这个选项，两边对得上，不用额外处理。

**5. `*_ownership_history.saved_by`：int → login_id/owner_code 字符串**
旧表这一列是纯数字 ID，没有额外字段说明这个 ID 到底是 `owner.id` 还是 `user.id`。核实了这次数据里 `owner.id` 范围是 3–169，`user.id` 范围是 216 起步，两边不重叠，所以用 `COALESCE(owner.owner_code, user.login_id)` 分别按 ID 查两张表、取非空结果，是安全、无歧义的（前提是以后旧库数据的 ID 范围不发生变化，不能默认永远成立，只是这批数据里刚好如此）。

**6. 悬空引用（旧库本身的数据问题，非迁移 bug）**
`company_ownership`/`group_ownership` 里有 7 条记录引用的 `company_id`（253、138、139）或 `group_code`（SS、AA）在旧库 `company`/`groups` 表里根本不存在——对应的公司/集团本身已经被删了，股权记录没跟着清掉。这 7 条自然搬不过去（目标 tenant 都不存在），已确认是旧库遗留脏数据，不是脚本漏了。

### 迁移结果

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| currency | 67（含 12 条重复） | 55 | 去重生效 |
| account_currency | 1628 | 1628 | 一致，重复币别的引用已正确改指向存活行 |
| domain_list_fee_price | 1 行设置里 3 个非空价格 | 3 | 只迁有值的 |
| announcements | 4 | 4 | 一致 |
| maintenance_marquee | 1 | 1 | 一致（旧 `label_type` 全部是 `maintenance`，新表没这列，确认安全丢弃） |
| tenant_ownership | 23（7 条悬空） | 16 | 差值=悬空数据 |
| tenant_ownership_history | 7（1 条悬空） | 6 | 同上 |
| tenant_fee_share_allocation | — | 35 | JSON 拆解正常（`sales`/`cs`/`profit`；`it` 分类这批数据全是空数组，没有产出行） |
| account_link | 74 | 74 | 一致 |
| tenant_auto_renew | 10 | 10 | 一致 |

### 明确没做、留到后面的部分

- **`tenant_auto_renew_transaction`**：需要关联 `transactions.id`，Transactions 域还没迁，等那边做完再补。旧表 `company_auto_renew_request` 里的 `from_account_id`/`to_account_id`/`transaction_id`/`reject_reason` 在新 `tenant_auto_renew` 里都没有对应列，这几个字段本身也不迁。
- **`account_currency_display_order`**：16 行里 11 行的 `account_id` 是负数（哨兵值，不对应任何真实 `account.id`），JSON 格式也是按 company_id 分组的对象而非扁平数组——这 11 行不是真实的"某个账号的币别排序偏好"，跳过不迁。真正处理的是另外 5 行（扁平数组、`account_id` 为正），已折入 `account_currency.sort_order`。

---

## 4. Process 域（`migrate_data_process_from_legacy.sql`）

依赖第 2、3 节的结果（`tenant`/`owner`/`user`/`account`/`currency` 已经迁完）。**只覆盖 GAME 类别**——BANK 类别 process 来自旧表 `bank_process`，属于后面的 Bank Process 域，另开脚本处理。

### 覆盖范围
`process_description`（从 `description`）、`process`（从 `process`，`category='GAME'`）、`process_description_link`、`process_day`、`process_submitted`（从 `submitted_processes`）。

### 写脚本前确认过：`description` 表本身没有重复

按你的要求先查了一遍 `description` 表——`company_id`+`name` 精确匹配和大小写/空格归一化匹配都没有撞的，没有重复，可以直接迁。

`process` 表本身则在 `(company_id, process_id)` 组合上有 **155 组、共 465 行**重复（比如公司 300 的 `process_id='WCC'` 单独就有 21 条）——这部分的排查、取舍过程见下方 §4.1/§4.2（原本这里记录过一版"合并成一条"的方案，后来推翻了，详情见 §4.1 开头）。

### 其他判断

- **`enable_save_draft`**（1147 行里 3 行是 true）：新 `process` 表没有这一列——新的 Draft 设计（`data_capture_draft`）把"是否允许存草稿"变成了按 process 类别/代码判断的业务规则，不再是挂在 process 上的开关，确认安全丢弃。
- **`sync_source_process_id`**（120/1147 行有值，旧系统"从上级 group 自动同步下来"的溯源字段）：借用了新增的 `process.copied_from_process_id` 列来存——这一列本来是给新版 "Copy From" 功能（手动复制）用的溯源字段，跟旧的"自动同步"不是同一个机制，但都是"这个 process 是从另一个 process 派生来的"这个意思，而且这一列本身就只做追溯用、不影响业务逻辑，风险低。
- **`created_by`/`updated_by`**：旧表用 `(int, type)` 两列表达"是 user 还是 owner"，解析逻辑复用了之前 Ownership 域 `saved_by` 用过的同一套做法，转成新表要求的 login_id/owner_code 字符串。
- **`process_day` 的 `day.id`**：确认 1=MON...7=SUN，跟新表 `day_of_week`（1=Mon...7=Sun）定义完全一致，直接搬，不用转换。

### 4.1 155 组重复：一开始想合并，后来推翻改成不合并、逐条保留

最初的方案是"每组重复挑一条存活记录、合并"（837 组、`_map_process` 映射、`_migration_archive_process_duplicates` 归档表）。后来核实了具体案例（`95@EA42` 那组）发现：这批"重复"绝大多数**不是**旧系统缺多对多设计的技术性重复，而是**业务上故意拆开的**——同一个业务码，不同的报表区块（体育/真人/电子游戏），各自有不同的解析规则（`replace_word_from`）和不同的分润公式，而且是同时并行在跑的。合并会把其中两份实际生效的解析规则从"活的"配置里丢掉。

**因此推翻了合并方案，改成不合并、逐条保留**（细节见下方）。原来的 837/`_map_process`/归档表相关内容已不适用，`_migration_archive_process_duplicates` 表也已经删除。

**改成不合并后的做法**（`migrate_data_process_from_legacy.sql` 重写）：

- 每条旧 `process` 记录 1:1 保留成一条新记录，`id` 直接沿用旧库数字 ID（目标表为空，不会撞号）
- `code` 允许重复（新 schema 配合改了约束，见 §5 "Process code 允许重复"这次改动），重复的按 `(company_id, process_id)` 分组、`id` 从小到大，第一条保留原样 code，其余依次加 `_1`/`_2`... 后缀区分
- 因为 ID 全部 1:1 保留、不合并，**之前记录的"Data Capture 域必须走 `_map_process` 映射"这条警示已经不需要了**——`data_captures.process_id` 直接用旧库原始数字 ID 就能对上新库对应的 process，不用转换

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| process_description | 544 | 544 | 一致 |
| process | 1147 | 1147→**1140**（见下方"真重复合并"） | 不合并，1:1 保留 |
| process_description_link | — | 1147→**1140** | 每条 process 对应自己的一条 description |
| process_day | 2720 | 2720→**2709** | 见下方 |
| process_submitted | 7605 | 7605（不变） | 一致 |

### 4.2 事后发现并处理：6 组"真重复"（不是业务拆分，是旧系统的真实 bug）

不合并之后，逐个抽查了截图里看到的那些重复 code，发现除了 `95@EA42` 这种"故意拆分、各自解析规则不同"的正常情况外，还有 **6 组、共 7 条记录，是同一个 `(code, description)` 组合真的重复了两三遍**（比如 `AB33888` 同一个 description "XE88 LC" 出现了 3 次）。

**排查这 6 组时的关键发现**：
- 这些记录在旧库里的 `status` **不是**字面上的 `inactive`，而是 `waiting` 或空字符串 `''`——这正好解释了你在旧版前端切 "Show Inactive" 看不到这些记录的原因：旧前端的筛选大概率精确匹配 `status='inactive'`，`waiting`/`''` 两边筛选都不命中，在旧系统里这些记录本来就是"看不见"的盲区，不是数据被删了
- `status` 完全不能拿来判断"哪条记录是真正在用的"——`AB33888` 那组最典型：当前 `status='active'` 的那条实际 0 次提交，真正有 1 次真实提交的反而是 `status='waiting'` 的那条
- 6 组里有 5 组是**同一件事按时间顺序换了个记录继续做**（比如 `EC23`：一条记录从 4 月每周提交到 8 月 16 号，共 11 次；8 月 23 号那一周开始换了另一条记录继续提交），不是无意义的废弃数据

**处理方式**（[fix_process_true_duplicates.sql](fix_process_true_duplicates.sql)）：每组选"旧库 `data_captures` 历史记录数最多"的那条作为存活记录（不是按 `status`），删除前把要删的记录名下的 `process_day`、`process_submitted` 先转移到存活记录（`process_submitted.process_id` 对 `process` 是 `ON DELETE CASCADE`，不先转移直接删会把已提交记录一起删掉）。

新建了一张**永久保留**的表 `process_duplicate_merge_map`（不是临时表），记录这 7 条旧 id 该合并到哪条——因为 Data Capture 域（真正的金额数据）还没迁，写那个脚本时必须 JOIN 这张表，把这几条历史金额正确转到存活记录上，否则会丢真实提交（已确认 `4538`/`4700` 这两个旧 id 各自对应 1 次真实的 `data_captures` 提交）。

合并后行数：`process` 1147→1140，`process_description_link` 1147→1140，`process_day` 2720→2709（去重合并，没丢任何一条排班），`process_submitted` 7605→7605（一条没少，2 条真实提交记录已确认正确转移到存活记录上）。

### 4.3 配套的 schema/应用层改动：`process.code` 允许重复

因为 §4.1 确认了"同一个 code 拆成几条、各自不同 description"是合法的业务场景，`process` 表原来的 `UNIQUE(tenant_id, category, code)` 约束改成了允许 code 重复、但 `(tenant, category, code, description)` 不能重复——用数据库触发器强制（不只是 Service 层校验，避免并发竞态绕过）。这是一次独立的 schema + 代码改动，不是数据迁移本身，完整细节记录在 [`TABLE_MIGRATION.md`](../sql/TABLE_MIGRATION.md) 的脚本索引里（`migrate_process_code_allow_duplicate.sql`），这里只是提一下它跟这次 Process 域迁移的关系：没有这次改动，§4.1 的"不合并、逐条保留"方案在新建 process 时会重新撞上旧的唯一约束。

---

## 5. Data Capture 域（`migrate_data_datacapture_from_legacy.sql` + `migrate_data_datacapture_draft_from_legacy.sql`）

依赖第 2、3、4 节的结果，**尤其依赖 `process_duplicate_merge_map`**（§4.2 建的永久映射表）——这一节就是当初留这张表的原因终于用上了。

### 覆盖范围
`data_captures`（header）、`data_capture_line`（从 `data_capture_details`，真实提交金额）、`data_capture_formula`（从 `data_capture_templates`，公式配置）、`data_capture_draft`/`data_capture_draft_cell`（草稿表，另开一个脚本）。

### 5.1 两个"字段存的其实是别的东西"的意外发现

写脚本前抽样验证字段含义时，发现两个字段名字看着像业务码字符串，实际存的是数字 ID（当字符串存）：

- `data_capture_details.account_id`：看着像账号业务码，实测全部 75234 行都是纯数字字符串，直接转成数字精确匹配 `account.id`（1:1 保留过的），不需要按业务码查 `account_tenant_access`
- `data_capture_templates.process_id`（以及 `data_capture_draft.process_key`）：同理，11342 行里 11336 行是纯数字字符串，是 `process.id`，不是业务码。少数几行是真的业务码文本（如 "SALARY"、"salary"、"commission"），这几个刚好是保留给 BANK 的固定码

如果一开始没抽样验证、直接假设是业务码去 JOIN，这两张表会大面积匹配失败或者匹配到错误的记录。

### 5.2 顺手发现并修的一个分类错误：21 条 process 应该是 BANK 不是 GAME

排查 `data_capture_templates`/`data_captures` 时发现，Process 域迁移时把全部 1147 条旧 `process` 记录都标成了 `category='GAME'`——但其中 21 条的业务码正好是新系统保留给 BANK 的固定码（SALARY/BONUS/PROFIT/COMMISSION），这些码在旧库的 `process` 表里本来就有真实的 Data Capture 记录（41 条 `data_captures`、278 条明细）。核实过这 21 条互相之间没有真重复（不需要走合并流程），直接把 `category` 改成 `BANK` 就行。

（这 21 条跟旧表 `bank_process` 完全无关——`bank_process` 是供应商合约表，字段是 `contract`/`day_start`/`profit_sharing` 这些，跟"BANK 固定码 process 锚点"是两回事，后者是给 Data Capture 用的。）

### 5.3 合并过的 7 个 process id：正确性核对

`data_captures.process_id` 全部通过 `process_duplicate_merge_map` 解析（`COALESCE(合并映射, 原始id)`），核对结果：
- 新库 `data_captures.process_id` 里已经查不到任何一个被合并掉的旧 id
- 具体验证：旧 `data_captures.id=19614`（原挂在 4700 下，08-23 那次提交）正确指向存活记录 4138；`id=14573`（原挂在 4538 下，06-28 那次提交）正确指向存活记录 4267；这两条 + 另一条共 3 次 capture 下面的 39 条 `data_capture_line`，旧库新库数量完全一致

`data_capture_formula` 这边合并触发了新的问题：`4267`/`4538` 合并后，两边各自对同样几个游戏商（"IG - TR8=PP"、"IG - ZBH3840=MCG"）配置过一模一样的公式，导致撞唯一键 `uk_dcf_tenant_process_formula`。抽查内容确认完全逐字段相同（不是配置分歧，是重复的配置副本），用 `INSERT IGNORE` 安全去重，11205 条可解析的里丢了 6 条纯重复，没丢任何有差异的配置。

### 5.4 Draft 表：JSON 手动展开（MariaDB 无 `JSON_TABLE`）

只有 13 条，但 `draft_json` 结构不统一（一半是 `{"headers":...,"rows":[...]}`，一半多包一层 `{"tableData":{...}}`），且是任意嵌套的表格数据（行数组第 0 项是行标签、其余是 `{"type":"data","value":...,"col":N}`）。查清楚全部 13 条 `rowCount` 都是 26、`colCount` 最大 22 后，用固定范围（行 0-25、列 0-20）的 `UNION ALL` 手动展开，只落库非空的 `data` 类型格子（按文档"空格不落库"的规则）。

`process_key` 解析跟 §5.1 一样分两种：纯数字走 `process_duplicate_merge_map`；文本码（salary/commission）按"这条记录自己的 tenant + category='BANK'"去匹配 `process.code`。

13 条里 8 条成功解析，5 条没有迁：1 条是 `company_id` 本身在旧库已经不存在（悬空引用）；4 条是 GROUP 类型租户 + 文本码（salary/commission）——GROUP 类型租户从来没有过 BANK 类型的 process（旧 `process` 表只按 company 建，GROUP 层级的 BANK 锚点是新系统运行时才会按需自动建，现在还没有），不是漏处理，是真的没有对应记录可迁。

### 迁移结果

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| data_captures | 12893 | 12893 | 一致 |
| data_capture_line | 75234 | 75234 | 一致 |
| data_capture_formula | 11342（6条NULL、131条悬空引用、11205条可解析） | 11199 | 差额=合并产生的真重复（内容核对完全相同） |
| data_capture_draft | 13 | 8 | 5 条见上方说明，原因已查明 |
| data_capture_draft_cell | — | 88 | 抽查内容与原始 JSON 完全对应 |

### 明确没做、留到后面的部分

- **`data_capture_description`**（GAME 多选 description 桥表）：旧库没有直接对应的源字段，需要从 `data_capture_details.description_main`/`description_sub` 反推、匹配回 `process_description`、按 capture 去重——目前还没做，准确度也不如其他表有把握。

---

## 6. 应用层发现的问题（与数据迁移相关，但是代码 bug 不是数据问题）

### 6.1 Argon2 密码 hash 没法登录 — **已发现，未修复**

旧库 `account` 表(member/UPLINE 账号)里密码字段混杂了三种格式：
- 明文（"123"、单字符等）—— 1483 条里超过一半
- `$argon2id$...`（PHP `password_hash(PASSWORD_ARGON2ID)`）—— 253+ 条
- `$2y$...` bcrypt —— 2 条

当前 `AuthServiceImpl.verifyPassword()` 只识别 `$2a$`/`$2b$`/`$2y$`（bcrypt）前缀，命中就走 `passwordEncoder.matches()`，其余一律退回明文比较 `raw.equals(stored)`。**这意味着 253+ 条 `$argon2id$` 密码的账号，迁移后永远登录不了**（明文比较对着一串 hash 字符串必然不相等）。用户 `user`/`owner` 表里同样发现了 `$argon2id$` 格式的密码（如 APPLE/9 那几个账号），所以这个问题不只影响 member 登录，admin/owner 登录也一样受影响。

**尝试过的修复方案**（已撤销，未采纳）：加一个 `Argon2PasswordEncoder` bean + Bouncy Castle 依赖（`org.bouncycastle:bcprov-jdk18on`，Spring Security 的 `Argon2PasswordEncoder` 运行时依赖这个库做实际的 Argon2 计算），扩展 `verifyPassword()` 按前缀分派到对应的 encoder。方案本身经验证是可行的（Argon2 的编码格式是跨语言标准 PHC 字符串，Spring 能解析 PHP 产生的 hash），只是加了一半被要求撤掉了，代码目前保持原样，问题还在。

**状态**：已确认存在，未处理，需要另外决定怎么修（或者要不要修）。

---

## 7. 脚本复用性

所有脚本都是纯 `INSERT INTO ... SELECT FROM 旧库.表`，没有 hardcode 任何具体数据，`login_id` 去重、`process` 去重都是现算的，不依赖手动预处理。

**能直接复用的场景**：重新导入一份新的旧库备份（换个 staging 库名，脚本里改一下引用的库名），对着一个全新建好、身份域为空的库跑。

**不能直接复用的场景**："目标库已经有数据，要追加/更新"——脚本目前都假设 `owner`/`tenant`/`account`/`user`/`currency`/`process`/`process_description`/`data_captures` 等表的 ID 可以直接保留旧库原值或重新分配，如果目标库已经有别的数据占了这些 ID，会撞主键（`testcount` 就是因为这个原因放弃、改用全新的 `count_real`）。如果以后有增量更新的需求，需要把"直接保留 ID"的部分也改成映射表方式。

---

## 8. 还没做的域

按 [`TABLE_MIGRATION.md`](../sql/TABLE_MIGRATION.md) 的顺序，接下来还有：`data_capture_description`（Data Capture 域收尾，见 §5 明确没做部分）。Transactions/RATE 已完成，见 §12；Bank Process 已完成，见 §13（含跳过数据清单 [`SKIPPED_DATA_TRANSACTIONS_BANKPROCESS.md`](SKIPPED_DATA_TRANSACTIONS_BANKPROCESS.md)）。

---

## 9. `login_id` 去重明细：孤儿 vs 在用（供人工核对/改名用）

第 2 节提到的自动去重（`ROW_NUMBER() OVER (PARTITION BY login_id ...)`）覆盖了旧库全表 **17 组** 重复 login_id，不是只有 APPLE/9/JS。下表按原始名称分组列出全部 55 条记录，`tenant` 列为空的就是孤儿记录（没有任何 `user_tenant_access`，进不了任何公司）；有值的是在用记录。**新 `id` 就是 `count_real.user.id`**，需要改名/核实的直接按这个 id 找。

| 原始名 | 新 login_id | id | name | role | status | 在用的 tenant（`tenant.code`） |
|---|---|---|---|---|---|---|
| 9 | `9` | 241 | 9 | ADMIN | ACTIVE | — 孤儿 |
| 9 | `9_1` | 251 | 1 | SUPERVISOR | ACTIVE | — 孤儿 |
| 9 | `9_2` | 252 | 9 | CUSTOMER_SERVICE | ACTIVE | — 孤儿 |
| 9 | `9_3` | 280 | 9 | MANAGER | ACTIVE | AG |
| 9 | `9_4` | 521 | KC | CUSTOMER_SERVICE | ACTIVE | C168 |
| 9 | `9_5` | 527 | 9 | ADMIN | ACTIVE | M1, M2 |
| ABC | `ABC` | 411 | ABCD | ADMIN | ACTIVE | — 孤儿 |
| ABC | `ABC_1` | 489 | ABC | SUPERVISOR | ACTIVE | — 孤儿 |
| APPLE | `APPLE` | 220 | APPLE | SUPERVISOR | ACTIVE | — 孤儿 |
| APPLE | `APPLE_1` | 244 | APPLE | ADMIN | ACTIVE | — 孤儿 |
| APPLE | `APPLE_2` | 250 | 1 | MANAGER | ACTIVE | — 孤儿 |
| APPLE | `APPLE_3` | 265 | APPLE | MANAGER | ACTIVE | 95 |
| APPLE | `APPLE_4` | 274 | APPLE | ADMIN | ACTIVE | — 孤儿 |
| APPLE | `APPLE_5` | 503 | LI PIN | PARTNERSHIP | ACTIVE | — 孤儿 |
| APPLE | `APPLE_6` | 522 | APPLE | CUSTOMER_SERVICE | ACTIVE | C168 |
| APPLE | `APPLE_7` | 528 | APPLE | ADMIN | ACTIVE | M1, M2 |
| BEE | `BEE` | 216 | YAO | ADMIN | ACTIVE | — 孤儿 |
| BEE | `BEE_1` | 255 | BEE | ADMIN | ACTIVE | 95, AG, RS, VG |
| BEE | `BEE_2` | 270 | BEE | ADMIN | ACTIVE | — 孤儿 |
| JK | `JK` | 218 | JK | PARTNERSHIP | ACTIVE | 95, AG, C168, CX, RS, VG |
| JK | `JK_1` | 271 | JK | ADMIN | ACTIVE | — 孤儿 |
| JK | `JK_2` | 505 | JK | ADMIN | ACTIVE | — 孤儿 |
| JS | `JS` | 287 | JS | MANAGER | **INACTIVE** | AG |
| JS | `JS_1` | 291 | JS | ADMIN | ACTIVE | — 孤儿 |
| JS | `JS_2` | 518 | JS | ADMIN | ACTIVE | — 孤儿 |
| JS | `JS_3` | 533 | JS | ADMIN | ACTIVE | BK1 |
| KAYDEN | `KAYDEN` | 228 | GW | MANAGER | ACTIVE | — 孤儿 |
| KAYDEN | `KAYDEN_1` | 266 | KAYDEN | SUPERVISOR | **INACTIVE** | VG |
| KAYDEN | `KAYDEN_2` | 293 | KAYDEN | ADMIN | ACTIVE | — 孤儿 |
| KAYDEN | `KAYDEN_3` | 508 | GW | PARTNERSHIP | ACTIVE | — 孤儿 |
| KY | `KY` | 219 | KAYDEN | SUPERVISOR | ACTIVE | — 孤儿 |
| KY | `KY_1` | 506 | KAI YUAN | ADMIN | ACTIVE | — 孤儿 |
| KY | `KY_2` | 542 | KY | MANAGER | ACTIVE | CX |
| MILO | `MILO` | 222 | MILO | SUPERVISOR | ACTIVE | — 孤儿 |
| MILO | `MILO_1` | 261 | MILO | SUPERVISOR | ACTIVE | RS |
| MOON | `MOON` | 221 | MOON | SUPERVISOR | ACTIVE | — 孤儿 |
| MOON | `MOON_1` | 264 | MOON | SUPERVISOR | ACTIVE | 95 |
| MS | `MS` | 294 | MS | ADMIN | ACTIVE | — 孤儿 |
| MS | `MS_1` | 515 | MS | PARTNERSHIP | ACTIVE | — 孤儿 |
| SEVEN | `SEVEN` | 223 | SEVEN | SUPERVISOR | ACTIVE | — 孤儿 |
| SEVEN | `SEVEN_1` | 260 | SEVEN | MANAGER | ACTIVE | RS |
| SH | `SH` | 510 | SHI HUI | ADMIN | ACTIVE | CX |
| SH | `SH_1` | 516 | SH | MANAGER | ACTIVE | — 孤儿 |
| TEST | `TEST` | 296 | TEST | PARTNERSHIP | ACTIVE | — 孤儿 |
| TEST | `TEST_1` | 298 | TEST | PARTNERSHIP | ACTIVE | — 孤儿 |
| TEST | `TEST_2` | 507 | TEST | ADMIN | ACTIVE | — 孤儿 |
| TEST01 | `TEST01` | 248 | TEST01 | ADMIN | ACTIVE | — 孤儿 |
| TEST01 | `TEST01_1` | 357 | TEST01 | MANAGER | ACTIVE | — 孤儿 |
| TEST01 | `TEST01_2` | 365 | TEST01 | ADMIN | ACTIVE | — 孤儿 |
| WINE | `WINE` | 275 | 9 | ADMIN | ACTIVE | — 孤儿 |
| WINE | `WINE_1` | 504 | KC | PARTNERSHIP | ACTIVE | — 孤儿 |
| ZERO | `ZERO` | 254 | ZERO | ADMIN | ACTIVE | 95, AG, VG |
| ZERO | `ZERO_1` | 267 | ZERO | ADMIN | ACTIVE | — 孤儿 |

**统计**：55 条里，25 条有真实 tenant 权限（"在用"），30 条是孤儿（无 `user_tenant_access`，登录了也进不去任何公司）。真正需要你判断"是不是同一个人、要不要重新起名"的，是**同一原始名下有 2 条及以上"在用"**的那几组——目前只有 `9`（3 条在用：`9_3`/`9_4`/`9_5`）和 `APPLE`（3 条在用：`APPLE_3`/`APPLE_6`/`APPLE_7`）符合这个条件；其余组即使有孤儿记录，"在用"的都只有 1 条，不存在互相冲突的问题，孤儿记录本身要不要留着（比如干脆设成 INACTIVE 或删掉）看你自己判断。

---

## 10. 事后修复：`tenant_feature_module` 整张表没迁，导致侧边栏 Data Capture / Maintenance 子菜单消失

### 现象

迁移到 `count_real` 后，用真实数据登录（如 `JK`，属于 `95`/`AG`/`C168`/`CX`/`RS`/`VG` 这几个 GAME 类别公司），侧边栏没有 "Data Capture" 入口，"Maintenance" 下也没有 Formula/Payment/Transaction Maintenance 这三个子菜单——即使 Edit User 页面上这个账号明确有 `Data Capture`、`Maintenance` 权限。之前用自己手工建的测试数据（`testcount` 库）跑同样的账号/权限配置是正常的，说明不是权限配置或代码逻辑的问题，是这批迁移数据本身缺了什么。

### 根因

`tenant_feature_module` 表（每个 tenant 属于 GAME 还是 BANK 业务模块，参见 `schema.sql`）**从头到尾没有被任何一个迁移脚本覆盖**——本文档 §1-§9 列出的所有域都没提到它。迁移跑完后这张表是空的（`SELECT COUNT(*) FROM tenant_feature_module` = 0）。

这张表直接决定 `hasGame`/`hasBank`（[`PermissionServiceImpl`](../../java/com/eazycount/service/impl/PermissionServiceImpl.java)，从 `TenantDao.findActiveFeatureModulesByTenantId` 查出来），而 `SessionUser.buildMenu`（[`SessionUser.java`](../../java/com/eazycount/security/SessionUser.java)）里 `dataCapture` 这一项的显示条件是 `keys.contains("datacapture") && (hasGame || hasBank || isGroupLogin)`——表是空的，`hasGame`/`hasBank` 恒为 `false`，所以哪怕角色权限里确实有 `DATACAPTURE`，这个入口也永远不显示。Maintenance 下的三个子菜单同理依赖同一个 `hasGame`/`hasBank` 判断。跟 §2 提到的"per-user 权限不迁"是两个独立问题——那个是故意的产品决定，这个是纯粹的遗漏。

### 数据来源 & 修复

排查旧库发现 `company.permissions`/`groups.permissions`（longtext，JSON 字符串数组）正好就是这个信息的原始来源，抽样确认取值只有 `["Games"]` 或 `["Bank"]` 两种、从不同时出现两个值、`company` 表这一列没有 NULL：

- `company.permissions` 含 `"Games"` → `feature_module.id=1`（GAME）
- `company.permissions` 含 `"Bank"` → `feature_module.id=2`（BANK）
- GROUP 类型 tenant 统一按 GAME 处理（跟 `DomainServiceImpl.ensureDefaultGroupFeatureModule`/`PermissionServiceImpl` 里"group ledger 永远按 Games 身份处理"的既有约定保持一致，`groups.permissions` 里非 NULL 的值也确实全部是 `["Games"]`，两边互相印证）

新增脚本 [`migrate_data_feature_module_from_legacy.sql`](migrate_data_feature_module_from_legacy.sql)，按 `tenant.code` 关联 `company.company_id`/`groups.group_code` 回填。已经在本地 `count_real` 跑过：迁移前 `tenant_feature_module` 0 行，跑完 28 行，正好等于 `tenant` 总行数（28 个 tenant 每个恰好 1 行），JK 名下的 `95`/`AG`/`C168`/`RS`/`VG` 都是 GAME、`CX` 是 BANK，跟旧库 `company.permissions` 一一对应。

**这个信息是在登录/切换 tenant 时算进 session（JWT）里的，不是每次请求实时查库**（见 `AuthServiceImpl.switchSessionTenant`/`SessionUser.from`），所以补完数据后，已经登录的账号需要重新登录一次或切换一次 tenant，侧边栏才会刷新。

### 遗留风险

`company.permissions`/`groups.permissions` 只在这次备份样本里验证过"必是单值、company 不为 NULL"，如果以后换一份旧库备份重跑，建议先按 §9 同样的思路抽样核对一遍这两个字段，再执行这个脚本。

---

## 11. 事后修复：Data Capture 草稿迁移 `col_index` 少算了 1，行标签文字（如 KAIYUAN/SHIHUI）在前端消失

### 现象

`CX` 公司 `SALARY` process 的草稿（Maintenance -> Data Capture 草稿功能）打开后，"1" 这一列本来应该显示行标签文字 `KAIYUAN`/`SHIHUI`，实际却显示金额 `3000`；`KAIYUAN`/`SHIHUI` 这两个值整个消失不见，视觉上像是所有列整体左移了一格。用自己手工建的测试数据在同一功能上是正常的，说明问题出在这批迁移数据本身，不是功能代码写错。

### 根因

`data_capture_draft_cell.col_index` 在新版后端里的约定是 **1-based**——`DataCaptureServiceImpl.normalizeCells`/`extractCellsFromTableData` 两处都把 `colIndex < 1` 当无效值直接丢弃，前端表格列头也是从 "1" 开始编号，即 UI 上的列 "1" 对应 `col_index=1`。

[`migrate_data_datacapture_draft_from_legacy.sql`](migrate_data_datacapture_draft_from_legacy.sql) 最初的版本按旧 JSON 里 `cidx.i`（0..20 的固定展开范围）**原样**存成 `col_index`，也就是 0-based——旧 JSON 数组里第一个 "data" 类型元素（对应 UI 列 "1"，比如 `KAIYUAN`）被存成了 `col_index=0`。这个值本身在数据库里是对的（没丢数据），但只要经过 `getBankDraft` 返回给前端，前端按"列头从 1 开始"的约定去匹配，`col_index=0` 的那一格找不到对应列头，直接被忽略掉；`col_index=1`（原本对应 UI 列 "2"，比如 `3000`）就顶替显示到列头 "1" 下面，造成"整体左移一格、行标签消失"的现象。

抽查全部 8 条已成功解析的迁移草稿（`draft_id` 1/17/23/26/28/30/39/53），`col_index` 全部是从 0 开始的，确认是这个脚本的系统性 bug，不是个例。

### 修复

- **迁移脚本**：[`migrate_data_datacapture_draft_from_legacy.sql`](migrate_data_datacapture_draft_from_legacy.sql) 里 `data_capture_draft_cell` 那段 INSERT 的 `col_index` 已经从 `cidx.i` 改成 `cidx.i + 1`，往后对着一个全新空库重新跑这个脚本会直接得到正确的 1-based 值，不需要额外补救。
- **已经跑过迁移、库里已有数据的情况**（这次本地 `count_real` 就是这种情况）：脚本改了不会回头修正已经插入的旧行，需要对现有数据做一次性订正：
  ```sql
  UPDATE data_capture_draft_cell SET col_index = col_index + 1;
  ```
  这条语句由用户本人在自己的 `count_real` 库上执行（未经 Claude 之手），覆盖当时全部 8 条迁移草稿。

### 遗留风险

这次订正是对"当前库里全部草稿行" `+1`，前提是这批草稿全部来自同一次有 bug 的迁移脚本、没有掺杂任何已经是正确 1-based 值的行（已核对：迁移当时 `data_capture_draft`/`_cell` 是从空表开始插入的，跑这条 `UPDATE` 时库里没有其它来源的草稿数据）。如果以后在同一个库上重复执行迁移脚本或手工修数据，不要不加判断地重复跑这条 `UPDATE`，否则会把已经是对的值再错误地 +1。

---

## 12. Transactions / RATE 域（`migrate_data_transactions_from_legacy.sql`）

依赖第 2、3 节的结果（`tenant`/`owner`/`user`/`account`/`currency` 已经迁完）。覆盖旧库 `transactions`、`transactions_rate`、`transactions_deleted`；`transaction_entry`/`transactions_rate_details` 按 [`TABLE_MIGRATION.md`](../sql/TABLE_MIGRATION.md) §2.6 是冗余的旧版双分录明细表，不迁——但 `transactions_rate_details` 在脚本里被当**只读桥表**用来找出 RATE 的两条腿分别是哪两行 `transactions`，本身内容不落库。

### 关于合并掉的 7 个 process id 的排查结论

迁移前先确认过：Process 域合并的那 7 个 id（`4700/4538/4687/4701/4175/4417/4590` → `4138/4267/4689/4176/4419/4591`，`process_duplicate_merge_map`）**跟这次迁移无关**——`transactions` 表（新旧两版）都没有 `process_id` 列，唯一可能扯上关系的字段 `source_bank_process_id` 实际指向的是旧库 `bank_process.id`（供应商合约表，完全不同的 id 空间，Bank Process 域还没迁），核对过没有任何一行 `transactions.source_bank_process_id` 命中这 7 个 id（0 条）。两条账本（Process/Data Capture vs Transactions）在这两版 schema 里都是彻底不相关联的，合并只影响前者，前者已经在 §4/§5 处理过了。

### 关键发现 / 处理方式

**1. `created_by`/`approved_by`：user 和 owner 两列可以同时有值**
旧表 `created_by`（int，指 `user.id`）和 `created_by_owner`（int，指 `owner.id`）不是互斥关系——抽查发现 11685 条里有 350 条两列都有值（核实过不是哨兵值，是真实存在的不同 owner）。新表 `created_by` 只有一个 login_id 字符串位置，脚本采用的优先级是**先 user 后 owner**（`COALESCE(user.login_id, owner.owner_code)`），依据是多数模式明显偏 user-only（8375+ 条 vs owner-only 2959 条）。这是一个记录在案的取舍判断，不是从数据里唯一能推出的答案，`approved_by`/`transactions_deleted.deleted_by` 同理处理。

**2. RATE 分组：靠 `transactions_rate_details` 反查两条腿是哪两行 `transactions`**
旧库没有像新表 `transactions.rate_group_id` 那样直接在 `transactions` 行上打标——要通过 `transactions_rate_details(rate_group_id, transaction_id, record_type)` 反查：`first_from`/`first_to` 两条 detail 都指向同一行 `transactions`（腿 1，即 `rate_from_currency`/`rate_from_amount` 那条，也正好等于 `transactions_rate.transaction_id`），`transfer_to` 指向另一行（腿 2，即 `rate_to_currency`/`rate_to_amount` 那条）。验证过全部 175 组里 173 组能这样干净地解出腿 1/腿 2（含 21 组带 middleman、1 组 middleman 是单条返佣而非"收+付"一对，都不影响腿 1/腿 2 的判定逻辑）。

**3. 2 组 RATE 天生缺一条腿，没法建 `transactions_rate` header**
`RATE_1779623477_1884`、`RATE_1786120634_9253` 这两组，旧库自己就只记录了一条 `transactions` 行（`first_from`/`first_to` 都指向它），压根没有 `transfer_to` 那条——不是脚本漏抓，是旧数据本身只做了一半（其中一组 `rate_to_currency_id` 跟 `rate_from_currency_id` 还不一样，不是"同币别走个形式"能解释的，像是旧系统的历史 bug 或没走完的提交）。新表 `leg2_transaction_id` 是 NOT NULL，没有第二条腿可指，**没有编造，这两组直接不建 `transactions_rate` header**；对应的那一条 `transactions` 行本身还是正常迁移了，只是没有 `rate_group_id`、也没有 RATE 搭档。

**4. `transactions_deleted`：62% 的行引用的 `company_id` 已经彻底不存在**
2907 条里 1805 条的 `company_id` 在 `company` 表里完全查不到——核实过不是这次迁移的问题：这些行 `deleted_at` 都在 2026-03，而旧库的"公司删除归档"功能（`company_deletion_archive`）最早的记录是 2026-06 才有，说明这批是**归档功能上线之前**就已经发生的公司删除留下的历史孤儿数据，当时没有任何机制保留这些公司的身份信息，现在没法倒推它们原本属于哪个 tenant。这部分**跳过不迁**（`tenant_id` 是 NOT NULL，编不出一个来）。此外还有 49 条（48 条 `transaction_type` 是空字符串、1 条是 `RECEIVE`）不是新枚举里的合法值，一并跳过。

**5. `bank_process_posted_id` 全部留空**
新表这一列指向的是"一次记账过账批次"（`bank_process_accounting_posted`），是全新的聚合概念，旧库 `source_bank_process_id`/`source_bank_process_period_type` 指的是单条 `bank_process` 记录，不是同一件事，而且 Bank Process 域本身还没迁（这张目标表还是空的）。回填这一列是以后做 Bank Process 域迁移时的事，这次不处理。

**6. `rate_expression`/`middleman_rate_expression`/`platform_fee_amount`：无旧库来源**
旧库只存了算好的数值（`exchange_rate`/`rate_middleman_rate`），没存用户当初在 UI 上敲的原始表达式字符串（如 `/1.703`）；`platform_fee_amount` 是这次备份之后才加的新功能。三个字段都留 NULL，没有编造。

### 迁移结果（对照旧库源记录数 / 校验）

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| transactions | 11685 | 11685 | 一致；`SUM(amount)` 迁移前后完全相等（82341881.47286000），金额没有算错 |
| transactions_rate | 175（2 组缺腿） | 173 | 差值 = 上述 §3 那 2 组，原因已查明，不是丢数据 |
| transactions（打了 `rate_group_id` 的行） | — | 346 | = 173 × 2，腿 1/腿 2 都打标成功 |
| transactions_deleted | 2907 | 1048 | 差值 1859 = 1805 条 company 不存在的历史孤儿 + 49 条枚举值非法（含 5 条重叠需人工核对时留意，未去重计算，实际跳过条数以脚本 WHERE 条件为准）|

### 明确没做、留到后面的部分

- **Bank Process 域**：`bank_process_posted_id` 回填依赖它，见上方 §5，现已部分完成，见 §13。
- **`transactions_deleted` 的 1859 条孤儿**：不可恢复，不打算再花时间，除非你有办法从别处（比如更早的备份）找回这些公司当时的身份信息。

---

## 13. Bank Process 域（`migrate_data_bank_process_from_legacy.sql`）——CRUD 部分已完成，Accounting Due 台账部分待你决定

依赖第 2 节的结果。覆盖 `bank_country`、`bank_option`、`bank_process`（核心字段）、`bank_process_share`、`bank_process_resend_daily_guard`。**没有覆盖** `bank_process_accounting_posted`（Due 台账）、`transactions.bank_process_posted_id` 回填、`bank_process` 的 `resend_schedule_*`（当前开放中的补单排程）——原因见下方 §13.2。

### 13.1 已完成部分的关键发现 / 处理方式

**1. `bank_country`/`bank_option` 是全新的按 tenant 下拉选项模型，旧库有两代并存的同功能表**
`country_bank`（188 行，自己的 id，无 sort_order）+ `company_countries`（36 行，只有国家）像是旧一代设计；`company_selected_countries`（50 行）+ `company_selected_banks`（130 行，复合主键 + sort_order）像是当前这一代。没有去猜"哪个是权威来源"，而是把四张表 + `bank_process` 自身用到的国家/银行全部 UNION 到一起，靠新表的 `UNIQUE(tenant_id, code)`/`UNIQUE(country_id, name)` 自动去重。`sort_order` 没有搬（新表没有对应列）。

**2. `bank_process.status` 合并了旧库的 `issue_flag`**
旧库状态是两个字段：基础 `status`（active/inactive/waiting）+ 一个独立的 `issue_flag`（block/official，一共 10 行），新表合并成一个 6 值枚举。处理规则：`issue_flag` 有值时优先于基础 `status`（核实过：凡是 `issue_flag` 有值的行，都是"曾经是 active，现在被 block/official 覆盖"这种更新的信号，不是矛盾数据）。

**3. `card_merchant_id`→`supplier_account_id`、`customer_id`→`customer_account_id`、`profit_account_id`→`company_account_id`，`cost`/`price`/`profit`→`supplier_price`/`customer_price`/`company_price`**
按字段语义对应（Buy Price=Cost=Supplier、Sell Price=Price=Customer、Profit=公司自己的毛利），和 `docs/frontend-springboot-migration.md` 里 Accounting Due 过账那段"Buy Price→Supplier、Sell Price→Customer、Profit→Company"的约定完全一致，不是瞎猜的对应关系。

**4. `profit_sharing` 自由文本解析成 `bank_process_share`**
格式是 `"代码 [名字] - 金额, 代码 [名字] - 金额"`，抽查全部 40 条非空行确认最多 2 段、代码本身不含连字符，用 `SUBSTRING_INDEX` 从空格/连字符切割是安全的。代码通过 `account_tenant_access` 限定在这条 `bank_process` 所在的 tenant 内解析成 `account.id`（`account_id` 是按 tenant 唯一，不是全局唯一）。解析出 56 行，跟源数据按逗号数展开后应有的行数（56）完全对上，没有静默丢行。

**5. 几个用量趋近于零、新表没有对应列的字段，确认后不迁**
`accounting_reactivated_floor_ymd`（1 行有值）、`issue_flag_locked_end_ymd`（0 行）、`accounting_resend_relax_created_floor`（1 行为真）、`accounting_resend_open_anchors`（0 行）——185 行里几乎不用，新 schema 也没有对应列，不迁。

**6. `expired_at_creation` 是重新算出来的，不是从旧库搬的**
旧库没有这个字段，新表注释给出了精确公式（"day_end 所在月份早于创建月份"），按公式在迁移时重新计算，不是编造。

### 迁移结果

| 表 | 旧库源 | 新库 | 备注 |
|---|---|---|---|
| bank_process | 185 | 185 | 一致 |
| bank_country | — | 9 | 四张旧表 + bank_process 去重后的并集 |
| bank_option | — | 46 | 同上 |
| bank_process_share | 40 行 profit_sharing 文本，展开后应有 56 行 | 56 | 一致，账号代码全部解析成功 |
| bank_process_resend_daily_guard | 89 | 81 | 差值 8 = 引用的 company 早已不存在（同 §12 transactions_deleted 那种"归档功能上线前的历史孤儿"模式），已确认不是脚本问题 |

### 13.2 没做、需要你决定的两块

**1. `resend_schedule_day_start`/`day_end`/`frequency`（当前开放中的补单排程）**
来源候选是旧表 `bank_process_maintenance_resend_pending` 里 `process_accounting_posted_id IS NULL` 的行（"还没过账"，54 行）。问题是：新表这三列的设计是"每个 bank_process 最多一条开放中的补单"，但旧数据里同一个 `bank_process_id` 有时有 2 条同时"还没过账"的行（比如 274 号同时有 monthly 和 day_end_tail 两条），而且这批数据的 `created_at` 都停在 2026-04，跟这次备份（2026-08-27）差了 4 个多月——没法确定这些是"现在真的还开着"还是"早就该清掉的过期脏数据"。这个需要你确认线上现在这几个 bank_process 的 Resend 状态该是什么，我不想瞎猜后端补单流程。

**2. `bank_process_accounting_posted`（Due 过账台账）+ `transactions.bank_process_posted_id` 回填**
旧表 `process_accounting_posted`（921 行，925 → 246 行能解析出有效 tenant+bank_process，其余是同样的"公司/process 早已不存在"历史孤儿）的 `period_type` 里有两个值我没能安全对应到新枚举：
   - `manual_inactive`（53 正常 + 7 skipped，是仅次于 monthly 的第二大类）——新枚举里没有这个值，猜测可能对应新设计里的 `COMPENSATION`（旧库这个名字可能是"手动改成 inactive 时的特殊出账"，新版重新设计成了 OFFICIAL/E_INVOICE/BLOCK 触发的"赔款"逻辑），但只是猜测，不敢直接落库
   - `resend_monthly_reopen`（`process_accounting_due_dismissed` 里 5 行）——同样没有对应
   
   另外，`monthly` 这个旧值本身是双关的：出现在 `frequency=monthly` 的 process 上时就是新枚举的 `MONTHLY`；但出现在 `frequency=1st_of_every_month` 的 process 上时，得看 `posted_date` 是不是等于 `day_start` 所在月份，才能判断该落成 `FIRST_MONTH` 还是 `FULL_MONTH`——这部分逻辑是可以写的，只是绑在上面两个未确认的枚举值一起，想等你给了答案后一次写完，不想先写一半。
   
   `transactions.bank_process_posted_id` 的回填倒是有干净的关联路径——旧库 `transactions.source_bank_process_id` + `source_bank_process_period_type` + `transaction_date` 能对回 `process_accounting_posted` 的 `(process_id, period_type, posted_date)`（抽查过是可靠的一一对应），等 Due 台账那部分定下来就能顺带一起做。

### 13.3 §13.2 两个问题的答案：去旧版 PHP 代码（`C:\Users\User\OneDrive\Desktop\count168test`）核实后确认，已完成迁移（`migrate_data_bank_process_accounting_due_from_legacy.sql`）

按你的要求，先去旧版 PHP 后端代码核实了这两个字段的真实用途，再动手写脚本，没有猜。

**1. `manual_inactive` → `COMPENSATION`，确认无误，但这次迁移里实际 0 行受影响**

`docs/bankprocess-accounting-due-lifecycle-rules.md`（旧版仓库自己的文档）明确写着"1+1/1+2/1+3 合同在设为 Official/E-Invoice 时的一次性违约金入账（`manual_inactive`）逻辑保留不变"；`process_post_to_transaction_api.php` 里 `manual_inactive` 就是字面意义上的"1+N 合同违约金"倍数入账逻辑（`getManualInactiveMultiplierFromContract`）。你现在 Spring Boot 这边 `BankAccountingDueServiceImpl` 已经有一整套成熟的 `COMPENSATION` 实现（`resolveOnePlusCompensationDue`/`postCompensationPeriod`/`settleCompensationSlot`），就是这个逻辑的直接后继版本，不是巧合。

**但排查数据后发现一个意外情况**：旧库 60 条 `manual_inactive`（53 正常 + 7 skipped）全部属于"公司/process 已经不存在"的那批孤儿数据（§12/§13 反复出现的"归档功能上线前的历史孤儿"），**一条都不在能解析出有效 tenant+bank_process 的 246 条里**。也就是说这次迁移里 `COMPENSATION` 这个映射虽然确认无误、脚本里也写了，但实际迁移结果是 0 行——不是脚本没做，是这批数据本身已经全部报废，捞不回来了。

**2. `resend_monthly_reopen` → 正式入账时旧代码本来就会归一化成 `monthly`**

`dismiss_accounting_due_api.php` 里明确写着：`if ($periodType === 'resend_monthly_reopen') { $periodType = 'monthly'; }`——所以这个值只会出现在 `process_accounting_due_dismissed`（skip/dismiss 记录），从来不会真正落到 `process_accounting_posted` 里。处理方式：跟普通 `monthly` 一样走 `FIRST_MONTH`/`FULL_MONTH`/`MONTHLY` 的判断逻辑，outcome 一律 `SKIPPED`。

**3. 补单排程（原以为的"54 条冲突数据"是我看错了表，真正的开放排程只有干净的 1 条**

去读 `maintenance_accounting_resend_lib.php` 才发现，`bank_process_maintenance_resend_pending` 那 54 条"`process_accounting_posted_id IS NULL`"的行，根本不是"当前开放中的补单排程"——那张表实际是"这笔已过账的记录是不是从某次 Resend 批次来的"审计/清理索引（给 Maintenance 删除功能用的），跟"现在有没有开着的补单"是两回事。真正代表"当前开放中"的信号是 `bank_process.accounting_resend_relax_created_floor`（布尔开关）+ 对应的 `accounting_resend_schedule_day_start`/`_day_end`/`_frequency`（或更新的 `accounting_resend_open_anchors` JSON，这批数据里没人用，0 行）。核对下来，**全库只有 1 个 `bank_process`（id 420）真的处于"开放中"状态**，排程本身干净、不冲突（`2026-05-31 ~ 2026-06-15`，`1st_of_every_month`），已经直接回填进 `bank_process.resend_schedule_*` 三个字段，不需要任何冲突取舍。

### 13.4 迁移过程中顺手发现并修的两个坑

- **`_skipped` 后缀截取有 bug**：一开始用 `REPLACE(period_type, '_skippe', '')` 想顺便处理那个拼写错误的 `resend_consolidated_range_skippe`（缺个 d），结果这个 7 字符的 `_skippe` 也是正确拼写 `_skipped`（8 字符）的子串，把 `day_end_tail_skipped` 这种正常值也错误截断成 `day_end_taild`，落到所有 CASE 分支之外变成 NULL。改成显式处理那一个拼写错误的值 + 按长度截取正确拼写的 `_skipped` 后缀，不再用容易误伤的子串替换。
- **`process_accounting_posted` 与 `process_accounting_due_dismissed` 大量重叠**：核对发现 `due_dismissed` 里有 10 行跟 `process_accounting_posted` 自己的 `*_skipped` 行指向同一个 `(bank_process, 日期)`，且全部归一化后是同一个新枚举值——`due_dismissed` 更多是重复记账，不是独立的第三方数据源。新表的唯一键放不下两份，所以 §3 的插入加了"§2 已经占了这个位置就不再插"的判断。另外发现 `bank_process.id=420` 自己在 `process_accounting_posted` 里就有一条 POSTED（2026-06-01 入账）和一条更晚的 SKIPPED（2026-07-24）指向同一个日期/类型——判定 POSTED 应该保留（它带着真实的 transactions），更晚那条 SKIPPED 更像是重复处理的痕迹，用 `ROW_NUMBER()` 让 POSTED 胜出。

### 迁移结果

| 表 | 结果 |
|---|---|
| bank_process.resend_schedule_* | 1 行（id=420）回填 |
| bank_process_accounting_posted | 255 行（245 来自 process_accounting_posted 去重后 + 10 来自 due_dismissed 里跟前者不重叠的部分） |
| ├ POSTED | 184 |
| └ SKIPPED | 71 |
| transactions.bank_process_posted_id 回填 | 494 行（509 条带 `source_bank_process_id` 的 transactions 里，494 条能对回一条有效的 POSTED 记录，其余 15 条对应的 posted 记录本身也是孤儿，回填不了） |

脚本：[migrate_data_bank_process_accounting_due_from_legacy.sql](migrate_data_bank_process_accounting_due_from_legacy.sql)。至此 Bank Process 域全部完成，Transactions/RATE（§12）+ Bank Process（§13）两个之前排在优先级最前的域都已经迁完。

---

## 14. 事后修复：CX/TRAVELMINI 出现 3 条不该存在的过期 Accounting Due

### 现象

迁移后登录 CX 公司查看 Accounting Due，弹窗显示 5 条待处理，其中 3 条是 TRAVELMINI SDN BHD（RHB）、日期分别是 18-03-2026 / 01-04-2026 / 01-05-2026——全部是已经过去几个月的旧账期。旧版 PHP 同一个入口显示 0 条。

### 排查过程（记录一下，因为中间绕了弯路）

一开始怀疑是 §13 提到的 `posted_date` 记账约定不一致（月初 vs 月末），后来发现这个怀疑本身是错的——用 `SELECT *` 直接看查询结果时，`DATE` 类型字段被序列化成了 `2026-06-30T16:00:00.000Z` 这种带时区偏移的 ISO 字符串，肉眼读成"6月30日"，但改用 `DATE_FORMAT(col,'%Y-%m-%d')` 拿到不受这层转换影响的真实值后，实际存的是 `2026-07-01`——纯粹是我自己读错了工具返回的日期显示，不是数据库里的值有问题（迁移脚本本身走的是 SQL 到 SQL 的直接搬运，没有经过这层 JS 序列化，数据一直是对的）。

排除这个乌龙之后，去对照旧版 PHP 源码（`process_accounting_inbox_api.php` 第 1524 行）才找到真正原因：**旧版 Accounting Due Inbox 对"1st of Every Month"这类周期性账单，只会显示"当前自然月"这一期**（代码注释原文："非 Resend：仅当前自然月；Resend 多期可回补历史月"）——月份一旦翻篇，旧系统就不会再主动提起没处理的旧账期，除非用户手动发起 Resend。你现在 Spring Boot 版的 `BankAccountingDueServiceImpl.resolveFirstOfMonthDues()` 则是从 process 创建月一直循环到今天，只要某个月在账本里找不到匹配的 POSTED/SKIPPED 记录就会一直显示——这是两边设计上的真实差异，跟这次迁移的数据本身无关。

已经跟你确认过：保留新版"不漏账"的行为（不改代码），但需要把旧库里因为 Resend 被 Skip 之后、底下单独月份从来没有被真正结算过的历史空档补上 SKIPPED 标记，避免它们被新版的"更严格"逻辑翻出来当成新的待办。

### 排查范围与结果

查了全库 `bank_process_accounting_posted` 里 `period_type='RESEND_CONSOLIDATED'` 的记录，只有 **1 个** `bank_process`（id=189，CX 公司，TRAVELMINI SDN BHD / RHB）的 Resend 请求最终是 `outcome='SKIPPED'`（其余全部是 `POSTED`，代表真的收到了合并账单的钱，是正常结清状态，不是这次要处理的问题）。

针对这一条，手工按 `buildFirstOfMonthDueForMonth` 的公式逐月核对（`day_start=2026-03-18`、`day_end=2026-09-17`、状态 ACTIVE、两个 cap 开关都是 0），确认 6/7/8 月都已经有正常的 `FULL_MONTH POSTED` 记录，只有 3、4、5 月这三期在账本里完全找不到任何记录（旧库里唯一相关的只有一条 2026-03-01 的 `RESEND_CONSOLIDATED SKIPPED`，类型对不上，卡不住新版的逐月精确匹配）。

### 处理

新增脚本 [fix_bank_process_resend_skipped_due_gaps.sql](fix_bank_process_resend_skipped_due_gaps.sql)，给 `bank_process_id=189` 补了 3 条 SKIPPED 记录：

| posted_date | period_type | outcome |
|---|---|---|
| 2026-03-18 | PARTIAL_FIRST_MONTH | SKIPPED |
| 2026-04-01 | FULL_MONTH | SKIPPED |
| 2026-05-01 | FULL_MONTH | SKIPPED |

`created_by` 留空（不是真实用户操作，不编造操作人），`created_at` 用脚本执行时间（这本来就是"现在"做的一次数据订正，不是历史上发生过的事，不倒填日期）。脚本本身用 `NOT EXISTS` 做了幂等保护，可以安全重跑。

跑完之后 CX 的 Accounting Due 应该只剩另外 2 条（跟 TRAVELMINI 无关的两个 process），已经不再出现 TRAVELMINI 这 3 条。

### 遗留说明

这次只处理了"Resend 被 Skip 导致底下月份空档"这一种模式（全库排查后确认只有这 1 个 process 符合）。如果以后还有别的 process 出现类似"过期 due 意外冒出来"的情况，大概率是同一类问题（旧库里这个月从来没有被真正 Post 或 Skip 过），可以用同样的思路排查：查 `bank_process_accounting_posted` 里有没有对应月份的记录，没有就照这个格式补一条 SKIPPED。

---

## 15. Payment History 里 RATE 类型的 CR/DR 正负号，新旧版本刚好相反（已按用户确认改回旧版规则）

排查 CX 公司 Payment History 显示金额跟旧版不一致时发现的。

### 现象

同一笔 RATE 交易（比如 AG/APEX GAMING 那笔 "EXCH RATE 3.085 SGD 2300 > MYR"），旧版显示 -7,095.50，新版显示 +7,095.50——金额一致，符号相反。

### 根因

对照旧版 PHP 源码 `history_api.php`：
- `PAYMENT`/`CONTRA`/`CLEAR` 三种类型：**To 账户显示负数、From 账户显示正数**
- `RATE` 类型单独反过来：**To 账户显示正数、From 账户显示负数**（第 2199-2207 行，跟上面三种刚好镜像）

新版 `TransactionHistoryMapper.xml` 的公式：
```sql
CASE
  WHEN account_id = #{accountId} THEN -amount   -- To 账户 = 负数
  ELSE amount                                    -- From 账户 = 正数
END
```
这条公式对 PAYMENT/CONTRA/CLEAR 是对的，但**RATE 也套用了同一条**——注释写"Cr/Dr = To−/From+（同 PAYMENT）"，看起来是有意把 RATE 的符号规则统一成跟 PAYMENT 一样，但这跟旧系统里 RATE 一直反着来的实际行为不一致。

已核实：`transactions.account_id`/`from_account_id`/`amount` 本身迁移得完全正确（跟旧库逐字段比对一致），这单纯是 Payment History 计算 CR/DR 符号时的规则差异，不是数据问题。

### 处理

用户确认："TO 显示 +，From 显示 -"是对的（也就是旧版 RATE 的规则），按这个改。改了 3 处 mapper（`TransactionHistoryMapper.xml`、`TransactionSearchMapper.xml`，两个文件的 `manualCrDrTransactionTypes` 片段本身就是故意重复维护的两份拷贝，改动同步了两边）：

1. **`TransactionHistoryMapper.xml` / `aggregateDomainPaymentBfByAccount`**（B/F 期初余额）：两个 UNION 分支的 `bfPart` 都加了 `CASE WHEN transaction_type='RATE' THEN <反过来> ELSE <原来> END`。这个函数本来就用 `rateTransferLegOnly` 把 Middle-Man 费用行过滤掉了，只处理 leg1/leg2，所以直接按 `transaction_type='RATE'` 判断就够，不需要额外条件。
2. **`TransactionHistoryMapper.xml` / `findDomainPaymentHistoryLines`**（明细行）：这个函数**没有**过滤 Middle-Man 费用行（连费用行一起显示），所以不能直接按 `transaction_type='RATE'` 判断——同一个 RATE 类型下，leg1/leg2 换汇腿和 Middle-Man 费用行的 `account_id`/`from_account_id` 语义是反过来设计的（费用行故意把"付钱的那个账户"塞进 `account_id`，"收钱的中间人"塞进 `from_account_id`，这样费用行才能直接吃 PAYMENT 那套 To−/From+ 公式算对），如果无脑对所有 RATE 都翻转符号，会把已经算对的费用行搞错。改成用 `t.rate_group_id IS NOT NULL` 做区分——只有真正的 leg1/leg2（这两条才会挂 `rate_group_id`）才翻转，Middle-Man 费用行（`rate_group_id` 恒为 NULL）维持原来的公式不变。
3. **`TransactionSearchMapper.xml` / `aggregateDomainPaymentCrDr`**：同第 1 点，这个函数也用了 `rateTransferLegOnly`，直接按 `transaction_type='RATE'` 判断即可。

### 验证

用 §16 提到的两个真实例子直接跑了一遍新公式核对：
- AG（APEX GAMING）那笔 RATE 腿：`-7095.50`，跟旧版一致
- BC009 那笔换汇腿 + 中间人费用行：`-9300.00 + -105.00 = -9405.00`，跟旧版合并显示的单行 `-9405.00` 完全对上

改完之后跑了 `mvn compile` 确认能编译通过，两个 mapper XML 也单独做了 XML 合法性校验（改的时候手滑在注释里写了个 `--`，XML 规范不允许注释内出现连续两个减号，会导致 MyBatis 启动直接报错——已经改成不含 `--` 的写法，属于顺手修的笔误，不影响业务逻辑）。

---

## 16. 事后修复：22 条"自己转自己"的 RATE Charge 记录，回填真实的中间人账户

延续 §15 排查过程中一起发现的问题，这次**已经执行**。

### 现象

BC009 (BILLION PAY SGD) 账户的 Payment History 里，一条 "RATE CHARGE (X0.035) FROM SGD 3000.00" 记录金额显示为空、余额没有变化，实际应该有 105 的费用扣款。

### 根因

旧库这类记录本身 `account_id` 和 `from_account_id` 就是同一个账户（自己转自己）——核实过不是迁移搬错的，旧库原始数据就是这样。新版 `TransactionHistoryMapper.xml` 的 CR/DR 公式里有一条 `WHEN account_id = from_account_id THEN 0`，遇到这种自引用记录直接归零，105 块费用因此从账本上消失。用户确认这条归零规则本身保留不改（§15 的回答之一），但 `from_account_id` 应该指向真正收这笔费用的中间人账户，而不是付费账户自己。

好消息是：**真正的中间人账户信息其实一直都在** —— `transactions_rate.middleman_account_id`（在 §12 迁移 Transactions 域时就已经从旧库 `rate_middleman_account_id` 正确带过来了），只是这些自引用的费用记录本身没有 `rate_group_id` 把它们跟对应的 RATE 头关联起来（按 schema 设计，中间人费用记录本来就不挂 `rate_group_id`，只有 leg1/leg2 那两条才挂）。

### 处理

新增脚本 [fix_rate_charge_self_referencing_from_account.sql](fix_rate_charge_self_referencing_from_account.sql)。排查全库后确认这类自引用 RATE 记录一共 **22 条**，按"同 tenant + 同一天的 RATE 头"匹配对应的 `transactions_rate.middleman_account_id`，金额最接近的那个头视为匹配（同一天同一 tenant 可能有多笔 RATE 提交，用金额打破平局）：

- **21 条**成功匹配并回填（19 条金额完全对得上；1 条有极小的小数点误差，唯一候选，安全；1 条金额对不上但当天当 tenant 只有这一个候选头，按排除法也是唯一解）
- **1 条**（id=2917，tenant 95，2026-03-16，108.84）在 `transactions_rate`、`transactions_rate_details`、`transaction_entry` 三张表里都找不到任何线索，无法恢复，保持自引用原状，没有编造数据

只改了 `from_account_id` 这一个字段，`account_id`/`amount`/`transaction_type`/`transaction_date`/`description` 都没动。脚本本身是幂等的（`WHERE account_id = from_account_id` 保证只影响还没修的记录），执行后确认全库只剩这 1 条自引用记录。

**⚠️ 这个修复后来被证明是错的，§17 撤销并给出了正确的方案，本节仅作历史记录保留。**

---

## 17. 修正 §16：中间人账户被重复入账两次（用户在浏览器里实测发现）

用户拿"ALL RATE"这个中间人账户（id=5496）自己的 Payment History 去核对时发现：同一笔 105 元的费用出现了两行（-105 和 +105），而旧版只有一行 +105（走 WIN/LOSS 栏，不是 CR/DR）。查下来 §16 那个修复的判断是错的。

### §16 错在哪

排查发现：这批"自引用"记录（§16 里改的 21 条）**旧库里其实每一条都有一个紧挨着的"兄弟行"**——一条独立的、单边的中间人入账记录（`account_id` = 中间人、`from_account_id` = NULL），跟自引用那条是同一天、同一笔费用、几乎相邻的 id（比如 BC009 例子里，`id=15200` 才是中间人的入账行，`id=15201` 才是那条自引用/待修的付款方行）。也就是说旧库对这一笔费用本来就是拆成两条独立记录存的：一条单边记我给中间人入账，一条单边记付款方扣款（迁移前是自引用，两边金额刚好抵消归零）。

§16 把付款方那条（`15201`）的 `from_account_id` 改成中间人账户后，等于让它也变成了"中间人 +105"的来源——而中间人自己那条独立记录（`15200`）本来就已经贡献了一次 +105，两条一起看，中间人被算了两次（+105 和 +105，通过一条 CLEAR -105 抵消后台账上偶然凑成 0，但明细行显示两条，跟旧版的一条对不上）。核对了另外 20 条也是同样的情况——排查全部 21 条，每一条都能找到这样一条紧邻的独立中间人入账行。

### 正确方案

`fix_rate_charge_self_referencing_from_account_v2.sql`：把 §16 改过的 21 条记录的 `from_account_id` 从"中间人账户"**改回 `NULL`**（不是改回自引用，是改成单边记录，`account_id` 还是原来的付款账户不变）。这样这条记录本身，靠 CR/DR 公式里"`account_id`=当前查看账户 → 负数"这条最普通的分支就能算出正确的 `-105`（付款方视角），不需要 `from_account_id` 指向任何人；而中间人那边继续靠它自己那条独立记录（`from_account_id` 恒为 NULL）单独入账 `+105`，两条各司其职，不会重复。

### 顺带发现并修的另一个 bug：中间人自己那条独立入账记录符号也是错的

核对中间人视角时发现：中间人自己那条独立记录（如 `15200`）在新版里被算成了 `-105`（应该是 `+105`）。原因是 `TransactionHistoryMapper.xml` 里原本就有一条"单边记录识别"逻辑（`rateMiddlemanFeeDescription`），专门把这种"`from_account_id` 为空、`account_id` 是中间人"的单边记录识别出来给 `+amount`，但这条识别逻辑只认新版自己生成的英文描述（`RATE_MIDDLEMAN_FEE`/`MARKUP X …`/`CHARGE … PLATFORM FEE`），认不出旧库迁移过来的原始文案（"Rate charge (x0.035) from SGD 3000.00"）——认不出就掉进了通用公式，被当成"付款方"处理，算成负数。

麻烦的是这条旧文案在中间人的入账行和付款方的扣款行上**完全一样**（两条记录 description 一字不差），没法靠文字区分谁是谁，所以新增了一条结构性判断——检查这一行的 `account_id` 是不是 `transactions_rate.middleman_account_id` 里登记过的、同一天同一 tenant 提交过的真实中间人账户，是就按单边入账处理给 `+amount`。这条新判断（`legacyRateMiddlemanFeeCredit`）加在 `TransactionHistoryMapper.xml`，只接到了 `findDomainPaymentHistoryLines`（明细行）这一处，因为这是用户实际在看、直接暴露问题的地方。

### 补充修复：Search/List 页（Contra Inbox 那个账户汇总表格）跟 Payment History 对不上

用户拿 Search/List 页（截图里 "Contra Inbox" 那个账户 × 币别汇总表格）核对时发现：`RATE`（中间人）账户 Win/Loss=0.00、Cr/Dr=-105.00、Balance=-105.00——跟 Payment History 里已经修好的 0.00（+105 的费用入账 与 -105 的 CLEAR 互相抵消）对不上。

**根因**：`aggregateManualRateMiddlemanWinLoss`/`aggregateManualRateMiddlemanCrDr`（Search/List 页汇总列）、`aggregateManualRateMiddlemanBfByAccount`（History 的 B/F 期初余额）这三处，判断"是不是中间人费用记录"用的是 `rateMiddlemanFeeLeg`（要求 `rate_group_id` 匹配），而这批迁移的旧记录压根没有 `rate_group_id`，结构上就完全捞不到——这批旧的中间人费用，在这三处的汇总数字里是完全"隐身"的（不多算也不少算，就是不出现）。截图里 `RATE` 账户显示的 `-105.00` 其实不是这笔费用算错了，是那笔 `CLEAR`（31/07，`-105`，正常类型不受影响）单独被算了进去，但抵消它的那笔 `+105` 费用入账因为"隐身"没被算，两边对不上，才凑出 `-105`。

**处理**：给这三处都加上跟 Payment History 同款的结构化判断（复用/新增 `legacyRateMiddlemanFeeCredit`、新增 `legacyRateMiddlemanFeeDebit` 两个片段，同时加进 `TransactionHistoryMapper.xml` 和 `TransactionSearchMapper.xml`，两边保持同步）：
- 中间人视角（`account_id` = 登记过的真实中间人）→ `legacyRateMiddlemanFeeCredit`，接进 `aggregateManualRateMiddlemanWinLoss`（Win/Loss `+105`）和 `aggregateManualRateMiddlemanBfByAccount` 的对应分支
- 付款方视角（同样的旧文案，但 `account_id` 不是任何登记过的中间人）→ 新增的 `legacyRateMiddlemanFeeDebit`，接进 `aggregateManualRateMiddlemanCrDr`（Cr/Dr `-105`）和 `aggregateManualRateMiddlemanBfByAccount` 新加的第四个分支

这样上次在 Payment History 记录的"已知还没处理的口子"这次也一起补上了。

### 验证

用同样的 SQL 逻辑手工核对了 BC009 例子和 AG-tenant（中间人账户 4640）那批：
- 中间人（5496）：只剩 `15200` 一条 `+105`（不再重复）
- BC009（5167）：leg2 `-9300` + 费用行 `-105` = `-9405`，跟旧版一致
- 中间人 4640 名下所有单边入账行（`5940`/`7044`/`7480`/… 共 18 条）全部核对是正数

`mvn compile` 通过；两个 mapper XML 又踩了一次注释里写 `--` 的坑（这次是新加的 `legacyRateMiddlemanFeeCredit` 片段注释），已经改成不含 `--` 的写法并重新校验通过。

### 补充修复：中间人那一行的 "ID PRODUCT" 列显示 "-"，应该显示 "RATE"

金额修对之后，用户又发现中间人（`5496`）那一行 `ID PRODUCT` 列显示 `-`，旧版是 `RATE`。

**根因**：`ID PRODUCT` 靠 `rateMiddlemanFee` 这个标记位判断——这个标记位原本只在 `tr`（`transactions_rate`）能通过 `rate_group_id` 关联上时才会算出来，而这批迁移的旧记录压根没有 `rate_group_id`，永远关联不上，标记位恒为 `false`，退回到按描述文字猜产品类型的兜底逻辑，旧文案 "Rate charge (x0.035)..." 又不在任何一条已知前缀里，最后只能显示空白。

**处理**：`rateMiddlemanFee` 标记位改成复用前面已经建好的 `legacyRateMiddlemanFeeCredit` 结构化判断（`account_id` 是不是某个 RATE 组登记过的真实中间人），这样中间人那一行会被正确标记为 `rateMiddlemanFee=true`。

**中间又踩了一个坑**：一开始图省事直接用"描述文字是不是 Rate charge 开头"来判断，结果中间人的入账行和付款方的扣款行描述文字**一模一样**，会把两边都标记成 `true`——而 `TransactionHistoryServiceImpl` 里 `isPlatformFee`（单边、无对手方 → 强制走 "Fee" + CR/DR 列）的判断条件当时只看"有没有 `from_account_id`"，两边都是 `NULL`，会把付款方那行也误判成走 WIN/LOSS 列显示正数——这就跟旧版"这笔费用对付款方应该是 CR/DR 里的负数"对不上了。改成结构化判断后，只有中间人那一行会被标记，付款方那一行留给下面这条新加的兜底规则处理。

顺手还发现并修了一个更早就存在的关联 bug：`isPlatformFee` 原来的判断条件（`isRateMiddlemanFee && from_account_id IS NULL`）没有检查描述文字，只要是单边记录就当成"Platform Fee"处理（固定走 "Fee" + CR/DR 列，不看 WIN/LOSS）。但这批中间人费用记录本质是"Service Fee/佣金"，不是真正的 Platform Fee，旧版是走 WIN/LOSS 列显示 "RATE" 的。加了一条描述文字里必须真的包含 "PLATFORM FEE" 字样的判断，两者才不会混在一起（`TransactionHistoryServiceImpl.java` 的 `isPlatformFee` 计算）。

付款方那一行（`15201` 这种）不会被标记成 `rateMiddlemanFee`，走的是另一条兜底路径——`domainProductFromDescription()` 原本只认识 "EXCH RATE " 开头的（新版自己重写过的换汇腿描述），不认识旧库原始的 "Rate charge (xN)..." 文案，加了一条 `RATE CHARGE` 前缀识别，映射到 `RATE`。

跑了 SQL 手工核对：`15200`（中间人）现在标记为 `true`，`15201`（付款方）标记为 `false`——各自按各自该走的路径显示，不会互相干扰。`mvn compile` 和两个 mapper XML 校验都过了。

### Search/List 页补充修复的验证

手工跑了新加的 `legacyRateMiddlemanFeeCredit`/`legacyRateMiddlemanFeeDebit` 判断条件：
- `15200`（中间人 5496）：`matches_credit_winloss = 1`，会被 `aggregateManualRateMiddlemanWinLoss` 算进 Win/Loss `+105`
- `15201`（付款方 5167）：`matches_debit_crdr = 1`，会被 `aggregateManualRateMiddlemanCrDr` 算进 Cr/Dr `-105`
- 中间人 `5496` 的净余额：Win/Loss `+105` + 原本就正常的 CLEAR Cr/Dr `-105` = `0`，跟 Payment History 的 Balance 对上了
- 确认不会被 `aggregateDomainPaymentCrDr` 重复计算（`rateTransferLegOnly` 仍然把这两条排除在外，只是这次不需要靠它，靠新的两个中间人专用函数覆盖）

`mvn compile` 通过，两个 mapper XML 校验都过了。

---

## 18. 重大补漏：Data Capture 明细全库 75234 条从未生成对应的 transactions 记录

用户在 KY (KAI YUAN) 账户核对 Payment History 时发现少了两笔 Data Capture 提交的 "SALARY : 3000" 收入记录。排查发现**这不是账户特定的问题，是 §5 Data Capture 域迁移当时漏掉的一步，影响全库**。

### 根因

旧版 PHP 的 Payment History 对 Data Capture Summary 的记录是**直接读 `data_capture_details`** 这张原始快照表，从来不需要一条独立的 `transactions` 记录。新版 Spring Boot 设计变了：`DataCaptureSummaryServiceImpl` 每提交一行，都会**同时**写一条真实的 `transactions`（WIN/LOSE）记录，`data_capture_line.transaction_id` 指回这条记录；`TransactionHistoryServiceImpl` 的 Payment History 也是照着这个新设计走的——直接读 `transactions` 表，`data_capture_line` 只用来补充 `idProduct`/`rateExpression` 这些展示字段，金额本身不读它。

§5 当时把 `data_capture_details` → `data_capture_line` 的行数据本身搬对了（75234 行，行数核对过），但没有为每一行补建对应的 `transactions` 记录，`data_capture_line.transaction_id` 全库 75234 行全部是 `NULL`。这个缺口在当时没被发现，是因为核对迁移结果时只对了行数，没有意识到新旧两版系统对"钱去哪了"这件事的读取路径完全不同。

### 排查范围

跑了一圈只读核对（不是猜的）：
- 全库 75234 条 `data_capture_line`，`transaction_id` 全部为 `NULL`
- 覆盖 12 个 tenant、926 个账户、12893 条 capture（GAME 12852 + BANK 41）
- 0 条孤儿：`capture_id`/`process_id`/`account_id`/`currency_id`/`tenant_id` 全部能正常关联，数据本身是干净的，纯粹是"从没生成过"，不是"生成了但断链"
- 732 条 `processed_amount=0`
- 全部 75234 条都有 `formula`
- 全部 12893 条 capture 都有 `created_by`

### 处理

新增脚本 [migrate_data_capture_line_transactions_backfill.sql](migrate_data_capture_line_transactions_backfill.sql)。字段映射完全照抄现在系统自己提交新数据时的规则（`DataCaptureSummaryServiceImpl.toTransaction()`/`toLineEntity()`），保证补出来的记录跟真实提交的长得一模一样：

| 字段 | 取值 |
|---|---|
| `transaction_type` | `processed_amount > 0` → WIN，否则 LOSE（跟现在系统 `signum() > 0` 判断一致；0 元的行也不特殊处理，照样生成一条 0 元 LOSE，跟真实提交行为一致） |
| `amount` | `ABS(processed_amount)` |
| `account_id`/`currency_id` | 明细行自己的，不是 header 的 |
| `transaction_date` | `data_captures.capture_date` |
| `description` | `process.code + ": " + formula` |
| `remark` | MAIN 行取 `description_main`，SUB 行取 `description_sub` |
| `created_by`/`approved_by` | `data_captures.created_by` |
| `approval_status` | APPROVED |
| `created_at`/`approved_at` | `data_captures.created_at`（这是真实发生过的历史事件，用当时的时间，不用 NOW()） |

**新记录 id 怎么定的**：这些交易从来没在旧库存在过，没有旧 id 可以保留。用 `AUTO_INCREMENT` 自动生成，靠"插入顺序 = `ORDER BY dcl.id`"配合事后 `ROW_NUMBER()` 重新算一遍同样的顺序，对应回 `data_capture_line.transaction_id`（单会话批量插入场景下 MySQL/MariaDB 保证 `INSERT...SELECT...ORDER BY` 按顺序分配自增 id，这是标准可靠做法，不是投机取巧）。

### 验证

跑完之后没有只信任逻辑，做了全量核对：
- `data_capture_line.transaction_id`：75234 条全部非空，且互不重复（75234 个 distinct 值，说明 id 对应关系没有错位）
- `transactions` 总数：`11685`（§12 迁移的）+ `75234`（这次补的）= `86919`，跟实际总数一致
- 金额加总：`SUM(ABS(processed_amount))` 跟新建 `transactions.amount` 的加总**分毫不差**（`131392887.48300675` = `131392887.48300675`）
- 逐行核对 `amount`/`account_id`/`transaction_date` 三个关键字段：**0 条不匹配**（75234 条全部核对）
- KY (KAI YUAN) 那两条：`account_id=5455`、`amount=3000`、`WIN`、日期 `2026-06-30`/`2026-07-31`，跟旧版截图完全对上
- WIN/LOSE 分布：40339 WIN + 34895 LOSE = 75234，加总正确

至此 Data Capture 域才算真正补完整——Payment History 现在能正确显示所有历史 Data Capture 收入/支出记录了。

---

## 19. 补做：细粒度账号/流程 ACL（`user_tenant_account_access`/`user_tenant_process_access`）

延续 §2 当时留下的口子——那时候 MariaDB 10.4 没有 `JSON_TABLE` 展不开 JSON，`process_permissions` 又要等 Process 域迁完才能对上号。现在两个阻塞都解除了，补上。

### JSON 结构

旧库 `user_company_permissions.account_permissions`/`process_permissions` 都是对象数组：
- `account_permissions`: `[{"id":4594,"account_id":"AG"}, ...]`——`id` 就是旧库 `account.id`（迁移时 1:1 保留过），不需要再按业务码反查
- `process_permissions`: `[{"id":4250,"process_id":"SALARY","description":"SALARY"}, ...]`——`id` 是旧库 `process.id`，`process_id` 这个字段名容易误导，实际存的是业务码文本，不是数字 id，忽略不用；`id` 需要跟其他所有引用过 `process.id` 的地方一样，走 `process_duplicate_merge_map` 解析

没有 `JSON_TABLE`，用固定 0..499 的数字表展开（这批 JSON 数组最长 416 个元素，够用），跟草稿迁移那次用的同一套手法。

### 处理规则

- `account_acl_mode`/`process_acl_mode`：数组非空 → `CUSTOM`；数组是空的 `[]` → `NONE`（核实过这是真实的"故意设成零可见"，不是占位——这一行数据存在本身就说明有人专门给这个用户+公司存过一条自定义权限记录）。`NONE` 和"`CUSTOM` 但关联表 0 行"在代码里效果完全一样（`UserServiceImpl` 里 `NONE` 直接短路返回空列表，`CUSTOM` 走 join 结果也是空），选 `NONE` 只是因为它把意图说得更明确，顺便省一次 join，不是瞎猜。

### 排查到的孤儿

42 条 `user_company_permissions` 里 5 条完全对不上任何 `user_tenant_access`：
- **3 条**（`user_id=523/524/525`，登录名 `IT_JK`/`IT_JS`/`IT_MS`）——这 3 个用户从来没有被搬进 `count_real.user`（旧库 95 个用户，新库当时只有 92 个，正好差这 3 个；核对过是 `system_it_allowlist` 那 3 条"IT 运维白名单"账号，`created_by=system-maintenance`，2026-07-10 创建，在这次备份范围内，理论上该被迁移脚本捞到但没有）。**已经跟你确认过：这 3 个账号你之前就说过要整个删掉，不需要，所以这次没有补，直接跳过。**
- **2 条**（`user_id=280`/公司 95，`user_id=299`/公司 CX）——这两个用户是真实存在的，公司也能解析到真实 tenant，但压根没有对应的 `user_tenant_access`（旧库 `user_company_map` 里也没有这两笔）——跟这次迁移里反复出现的"历史孤儿"是同一种模式（权限记录还在，但对应的访问授权已经被拿掉了），没有编一条 `user_tenant_access` 出来硬接上。

37 条可解析的行内部，还有零星失效引用：process 侧 5696 条条目里 62 条指向的 `process.id`（连 merge-map 都解析不出来）已经不存在，account 侧 5687 条条目里 172 条指向的 `account.id` 已经不存在——都是"整条权限列表基本有效、个别几条过期"的情况，跟这次迁移里其它域处理"列表里零星脏引用"的方式一致，直接跳过那几条，不影响列表里其它有效的条目。

### 迁移结果

| 项目 | 结果 |
|---|---|
| 触碰到的 `user_tenant_access` 行 | 37（跟可解析的行数一致） |
| `account_acl_mode` | 37 条全部 `CUSTOM`（这批用户的 account_permissions 都非空） |
| `process_acl_mode` | 28 条 `CUSTOM` + 9 条 `NONE` |
| `user_tenant_account_access` | 5515 行 |
| `user_tenant_process_access` | 5605 行 |

抽查了 `user_id=284`（在 CX 公司）：旧库 JSON 里 38 个账号，新库落地 37 条（1 条指向的账号已经不存在，跳过），跟预期完全对上。

脚本：[migrate_data_user_acl_from_legacy.sql](migrate_data_user_acl_from_legacy.sql)。

---

## 20. 补做：全库表清单复核发现的两处真实遗漏（`tenant_auto_renew_transaction` / `user_group_map`）——已执行

用户怀疑 `tenant_auto_renew_transaction`、`tenant_link` 这两张空表不该是空的，让重新核对。逐张核对后，`tenant_link` 确认不是迁移问题（见下方 20.3），但顺着这个思路把旧库**全部表名**（含未在任何文档/脚本里出现过的）跟迁移脚本、`TABLE_MIGRATION.md` 交叉扫了一遍，额外挖出一个之前完全没人发现的漏项（`user_group_map`）。两处都已核实、执行并验证。

### 20.1 `tenant_auto_renew_transaction`：§3 当时明确说"等 Transactions 域迁完再补"，后来没人回去补

§3 原文："`tenant_auto_renew_transaction`：需要关联 `transactions.id`，Transactions 域还没迁，等那边做完再补。" §12 把 Transactions 域迁完之后，这一步一直没人执行。

旧库 `company_auto_renew_request` 10 条（跟 §3 迁移结果一致）里，只有 1 条 `status='approved'` 且带 `transaction_id`：`id=2943`（公司 324 / tenant code `AJ`，`transaction_id=17044`）。核实：
- `transactions.id=17044` 在 `count_real` 里确实存在（`description="Renew AJ | 1 year"`，`amount=2400`，`tenant_id=77`）——说明这笔交易本身 §12 是搬对了的，只是关联行没建
- 对应的 `tenant_auto_renew` 记录也在（`id=3`，AJ 公司，approved），通过 `(tenant_id, expiration_snapshot)` 这个唯一键能精确对上
- 全库搜索 `description LIKE '%Renew AJ%'` 确认这笔续费只有这一条交易（旧系统一个申请只记一条流水，不是新版 `chargeDomainFee` 那种一次审批出付款/佣金/利润好几条腿的模式，所以这次只补 1 行是符合预期的，不是漏抓）

脚本：[fix_tenant_auto_renew_transaction_backfill.sql](fix_tenant_auto_renew_transaction_backfill.sql)（`NOT EXISTS` 幂等保护，可安全重跑）。

**执行结果**：插入 1 行（`request_id=3` ↔ `transaction_id=17044`）。已核对该行落库正确。

### 20.2 `user_group_map`：一张跟 `user_company_map` 平行、但从未被任何脚本或文档提到过的表

旧库除了 `user_company_map`（→ `user_tenant_access`，§2 已迁 50 行）之外，还有一张结构类似但独立的 `user_group_map`（3 行）——存的是"用户直接挂在某个 GROUP 租户下"的访问权限（不经过 company）。这张表在 §1-§19、`TABLE_MIGRATION.md` 里完全没有出现过，是这次全表名扫描才发现的。

核对这 3 行在 `count_real` 里的现状（迁移前）：

| user_id | login_id | 应有的 group | 迁移前 `count_real` 实际状态 |
|---|---|---|---|
| 532 | OK | LOL（tenant 112） | **完全没有任何 `user_tenant_access` 行**（这个账号哪个公司/集团都进不去） |
| 533 | JS_3 | LOL（tenant 112） | 只有 `BK1` 公司的权限，缺 LOL |
| 534 | BIN | IG（tenant 109） | 只有 `RS` 公司的权限，缺 IG |

（`LOL` 是 legacy `groups.id=18`，是构成 `count_real.tenant` 28 行里 5 个 GROUP 之一的真实集团，不是悬空引用。）

脚本：[fix_user_group_map_backfill.sql](fix_user_group_map_backfill.sql)。`account_acl_mode`/`process_acl_mode` 按 §2 同样的约定给默认值 `ALL`（`user_group_map` 本身不带任何 ACL 细节可还原，跟 §2 当时"迁移出来的 `user_tenant_access` 统一 ALL"的处理方式一致）。`NOT EXISTS` 幂等保护，可安全重跑。

**执行结果**：插入 3 行。已核对：532 现在能进 LOL；533 在原有 BK1（`CUSTOM`/`NONE`）之外新增了 LOL（`ALL`/`ALL`）；534 在原有 RS（`CUSTOM`/`CUSTOM`）之外新增了 IG（`ALL`/`ALL`）——原有行的 ACL 设置没有被覆盖，纯新增。

### 20.3 `tenant_link`：核实后确认不是迁移遗漏

用户同时怀疑的另一张空表。核实结论：
- `TABLE_MIGRATION.md` §5 本身就把 `tenant_link` 列为"新增，旧库无同名表"——没有 legacy 源表可迁
- 翻代码发现 `TenantOwnershipServiceImpl.linkPartner()`（"关联 Partner"功能的真正实现）实际写入的是 `tenant_ownership`（`owner_type='group'` + `partner_tenant_id`），全代码库搜索 `TenantLink`/`tenant_link` 没有任何地方真正读写这张表
- 进一步查了旧版 PHP（`count168test`）的对应表：`tenant_module_policy`（56 行，跟这次话题一起被怀疑过的另一张表）同样是"建了但从没被任何 PHP 代码读取过"的废弃脚手架（`database/migrations/20260528_dual_tenant_company_group.sql` 建表时批量灌的默认值，此后无人问津）

结论：`tenant_link` 空着是当前系统的正常状态，不需要补数据；这是应用层"预留了表但功能走了另一条路径"的情况，跟数据迁移无关。

### 迁移结果

| 表 | 执行前 | 执行后 |
|---|---|---|
| tenant_auto_renew_transaction | 0 | 1 |
| user_tenant_access（本次新增的 3 行） | — | +3（532/533/534 各新增 1 行 GROUP 权限，原有公司权限行不受影响） |

两个脚本均由 Claude 直接连接本地 `count_real`（`127.0.0.1:3306`，root）执行，执行前已用只读查询预演过 `SELECT` 部分确认结果集正确，执行后逐行核对落库结果与预演一致。

---

## 21. 诊断（未修复）：Payment History 页面 4 处应用层展示 bug——数据本身是对的，问题在 `TransactionHistoryMapper.xml`/`TransactionHistoryServiceImpl.java`

用户拿 `C168` 公司下 `AG`（APEX GAMING，`account.id=4839`，`role=COMPANY`）这个账户的 Payment History 页面（旧版 `count168.site` vs 新版 `localhost:5173`）逐行对比，发现好几处对不上，怀疑是迁移数据错了。逐条核对下来：**底层 `transactions`/`data_capture_line` 数据本身完全正确**（跟旧库逐字段比对一致），问题全部出在新版应用层的展示计算逻辑——是代码缺口，不是数据缺口。这里只记录诊断结论，代码本身按用户要求暂不修改。

排查方法：把新版代码对照旧版 PHP 仓库（`C:\Users\User\OneDrive\Desktop\count168test`）里真正驱动这个页面的 `api/transactions/history_api.php` 逐条核对（不是猜）。

### 21.1 Bug 1：PAYMENT 类型对 `[DOMAIN_LIST_FEE|...]`/`[AUTO_RENEW|...]` 标记行缺一条符号反转的例外

`transactions.id=7515`（"Pay Domain Fee"，`account_id=4837` C168 PROFIT 账户，`from_account_id=4839` AG，`remark='[DOMAIN_LIST_FEE|AG]'`）——数据本身没问题。

旧版 `history_api.php`（2118-2168 行）：PAYMENT 类型默认 **To 账户负、From 账户正**，但专门对 `sms`/`description` 带 `[DOMAIN_LIST_FEE|...]`（含 `[DOMAIN_LIST_FEE]`、`Pay Domain Fee`/`Pay Domain Fee To ` 开头的描述、`[AUTO_RENEW|...]` 系列）的行做了**反转**（注释原文："List Fee、Pay Domain Fee 付款方记 -amount"）；同一段落里 `[DOMAIN_SHARE_COMMISSION|...]`/`[AUTO_RENEW|COMMISSION|...]` 标记的行在 To 侧也有单独的 +amount 例外；`[DOMAIN_NET_PROFIT|...]`/`[AUTO_RENEW|NET_PROFIT|...]`/`Profit By ` 标记的行在 From 侧记 0。

新版 `TransactionHistoryMapper.xml` 的 `findDomainPaymentHistoryLines`（[TransactionHistoryMapper.xml:235](../mybatis/TransactionHistoryMapper.xml)）只有 §15/§16/§17 那次改的 RATE middleman fee 例外，domain-fee/auto-renew 这一整类例外从未被实现过——AG 作为 `from_account_id` 走的是默认的 From=正规则，显示成 `+2400` 而不是旧版的 `-2400`。

同样缺口存在于 `aggregateDomainPaymentBfByAccount`（B/F 期初余额聚合，[TransactionHistoryMapper.xml:104](../mybatis/TransactionHistoryMapper.xml)）——只要 B/F 截止日期之前有这类标记的交易，期初余额也会算错，不只是明细行。

**影响范围**：不只 AG 一个账户——所有租户下但凡有域名费自动扣款（`DOMAIN_LIST_FEE`）、自动续费扣费/佣金/净利润（`AUTO_RENEW|*`）、股权分成佣金（`DOMAIN_SHARE_COMMISSION`）的账户，Payment History 的这几笔金额符号和 B/F 都会算错。

### 21.1b 实锤：`K`(BOSS) 账户的 `AUTO_RENEW|COMMISSION` 那半个例外——CR/DR 符号 + ID PRODUCT + 描述文案三处一起错

用户又拿 `C168` 公司下 `K`（BOSS，`account.id=4844`，`role=PARTNER`）核对了一遍，正好是 §21.1 提到但当时没有具体例子的 `[AUTO_RENEW|COMMISSION|...]` 分支。数据（新旧库一致，迁移没搬错）：

```
transactions.id=17046  PAYMENT  account_id=4844(K)  from_account_id=4837(C168 PROFIT)  amount=240
description = "Sales Commision for AJ"   -- 注意：legacy 原文本身就拼错了（Commision 少一个 s），新库原样保留
sms/remark  = "[AUTO_RENEW|COMMISSION|AJ|2026-08-05|ROLE:SALES|AID:4844]"
```

**CR/DR 符号**：K 是 `account_id`（To 方）。§21.1 已经提到的例外——`sms` 以 `[AUTO_RENEW|COMMISSION|` 开头时，To 方记 **+amount**（`history_api.php` 2128-2133 行）。旧版显示 `+240.00`，新版因为 `findDomainPaymentHistoryLines` 没有这个例外分支，走了默认的 "To=负"，显示成 `-240.00`。跟 §21.1 是同一个代码缺口，这次是它的 To 侧半边被实锤到了。

**ID PRODUCT 全新发现的缺口**：旧版显示 `Commission`，新版显示 `-`（空）。根因不是符号问题，是另一套完全独立的逻辑缺失——`history_api.php` 对这类打了 `[AUTO_RENEW|COMMISSION|...]`/`[DOMAIN_SHARE_COMMISSION|...]` 标记的行，**根本不读存库的 `description` 字段来判断产品名**，而是从 `sms`/`remark` 里的标签重新解析：
- `historyResolveDomainShareRoleLabel()`（810-833 行）：解析 `sms` 里的 `|ROLE:SALES|`（还有 `PROFIT`/`CS`/`IT`）→ 得到角色 `"SALES"`
- `historyResolveAutoRenewCommissionSourceCompany()`（627-640 行）：解析 `sms` 里 `AUTO_RENEW|COMMISSION|AJ|...` 的 `AJ` → 来源公司代码
- 两者拼成 `$description = "SALES" . " Commission From " . "AJ"` = `"SALES Commission From AJ"`，同时 `$domainShareProductKind = 'Commission'` 就是 ID PRODUCT 显示的值（2515-2538 行）

也就是说旧版把这一整行的"产品名"和"描述文案"**都不认存库的 `description`**，全部靠 `remark` 里那个 `[AUTO_RENEW|COMMISSION|来源|日期|ROLE:角色|AID:账号]` 标签现算——这也解释了描述文案的第三处差异：旧版显示 `SALES COMMISSION FROM AJ`（角色 + "Commission From" + 来源公司，重新拼的），新版显示的是存库原文 `Sales Commision for AJ`（连拼写错误"Commision"都原样带出来了，介词也是 "for" 不是 "From"）——两个问题同源：新版既没有走"从 remark 重建文本"这条路径，也没有走 §21.4 的"按视角改写方向词"那条路径（这一行不匹配 `^(TYPE) (FROM|TO) (.+)$` 那个正则，因为它本来就不是标准 `"{TYPE} {FROM|TO} {账号}"` 格式，是自由文本）。

新版 `resolveDomainHistoryProduct()`/`domainProductFromDescription()`（[TransactionHistoryServiceImpl.java:391-434](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java)）目前只会拿存库 `description` 做字符串匹配兜底，其中还有个小 bug：第 405 行判断 `d.contains("COMMISSION")`（两个 S，拼写正确）——但这批数据实际存的是旧版一直沿用的错别字 `"COMMISION"`（一个 S），转大写后是 `"SALES COMMISION FOR AJ"`，压根不会命中这个 `contains("COMMISSION")` 判断，直接落空返回 `""`，前端显示 `-`。即使把这个拼写改成兼容两种写法，也只能治标——**真正要对齐旧版，需要照抄 `historyResolveDomainShareRoleLabel`/`historyResolveAutoRenewCommissionSourceCompany` 那套"从 remark 标签重建产品名和描述"的逻辑，而不是猜字符串**。

**影响范围**：所有 `[AUTO_RENEW|COMMISSION|...]`（自动续费佣金分成）和 `[DOMAIN_SHARE_COMMISSION|...]`（股权分成佣金）标记的交易——CR/DR 符号、ID PRODUCT、描述文案三处同时受影响，不只是 K 这一个账户。

### 21.2 Bug 2：History 合并排序用的是 `created_at`，不是 `transaction_date`

`TransactionHistoryServiceImpl.java` 第 236-238 行，三路来源（Bank Process / Data Capture / Domain Payment）合并后按 `createdAt` 排序：

```java
lines.sort(Comparator
        .comparing(TransactionHistoryLineRow::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
        .thenComparing(TransactionHistoryLineRow::getId, Comparator.nullsLast(Integer::compareTo)));
```

旧版 `history_api.php` 是按交易发生日期（`transaction_date`，"动态调整 description" 之前的显示日期变量 `$displayDateYmd`/`historyTransactionOrderTimestamp`）排序的。对迁移数据这两个字段经常对不上（补录/迟交），AG 这批数据里 `05/05` 那笔 `CONTRA`（`transaction_date=2026-05-04`，但 `created_at=2026-07-06`，操作员很晚才补录）就是因为 `created_at` 排序被排到了 `06/08` 那两条 Data Capture 记录后面，而不是按业务日期排在 `01/05` 和 `10/05` 之间。

**影响范围**：全租户所有账户——只要某笔交易的 `created_at`（数据录入/迁移时间）跟 `transaction_date`（业务发生日期）不一致，History 页面顺序就会乱，不只是迁移数据，正常业务里补录/延迟审批的单也会中招。

### 21.3 Bug 3：Data Capture 明细的 ID PRODUCT 只读了 `id_product`，没有像旧版一样兜底到 `id_product_main`/`id_product_sub`

核对 `data_capture_line`：AG 这几条迁移过来的记录（`transaction_id=42741` 等，对应旧库 `data_capture_details.id=66252` 等）**旧库本身 `id_product` 列就是空字符串**，但 `id_product_main`（"HONG MING SOON" 等）一直有值——这是**旧库本身的数据质量问题**（新旧库这一列的值完全一致，migration 没有搬错，是如实复制的）。

旧版 `history_api.php` 显示 ID PRODUCT 时压根不读 `id_product` 这一列（SQL 里根本没 SELECT 它，见 1512-1513 行），只用 `id_product_main`/`id_product_sub`：`product_type='sub'` 且 `id_product_sub` 非空 → 用 sub；否则 `id_product_main` 非空 → 用 main；都没有才兜底显示字面量 `'Data Capture'`（1916-1938 行）。

新版 `findDataCaptureHistoryLines`（[TransactionHistoryMapper.xml:209](../mybatis/TransactionHistoryMapper.xml)）SELECT 列表里只有 `dcl.id_product AS idProduct`，没有选 `id_product_main`/`id_product_sub`；`TransactionHistoryServiceImpl.java` 第 329-330 行只判断这一个空字符串，读不到就直接兜底成硬编码 `"DATA CAPTURE"`：

```java
String idProduct = trimToEmpty(line.getIdProduct());
row.setProduct(!idProduct.isEmpty() ? idProduct : "DATA CAPTURE");
```

没有走 `id_product_sub → id_product_main` 那条兜底链，所以旧库里 `id_product` 恰好是空的那批记录（AG 这几条是 5-7 月的，8 月的 `84258`/`84260` 凑巧 `id_product` 本身有值所以显示正常）在新版全部退化成没有意义的 `"DATA CAPTURE"`。

（用户一开始怀疑是"BANK 格式用 process code 查找"导致的——排查下来不是 process code 的问题，是 SELECT 的列本身就选错了，`process_id`/`process_code` 解析在这条链路上没有问题。）

**影响范围**：全租户——凡是旧库 `data_capture_details.id_product` 本身是空字符串的历史记录（不只 AG，抽查看是旧库长期存在的数据填写习惯问题，同一批数据里新旧记录都有），迁移过来后 ID PRODUCT 列都会显示成没有辨识度的 `"DATA CAPTURE"`，而不是实际的员工/项目名。

### 21.4 Bug 4：CONTRA/PAYMENT/CLEAR/RECEIVE/CLAIM/RATE 的 description 应该按"当前查看账户是 To 还是 From"动态改写，新版固定显示原始存库文本

核对 `transactions.id=14124/14126`（两笔 CONTRA，`account_id=4838` EXPENSES，`from_account_id=4839` AG）：**新旧两个库里存的 `description` 都是同一个字符串 `"CONTRA FROM AG"`**——不是迁移搬错了文本。

旧版 `history_api.php`（2365-2401 行）对 `CONTRA/CLEAR/PAYMENT/RECEIVE/CLAIM/RATE` 这几类做了一层"看当前是谁在查"的动态改写：
- 原始 `description` 为空 → 按当前视角自动生成 `"{TYPE} FROM {from_account_code}"`（当前是 To 账户）或 `"{TYPE} TO {to_account_code}"`（当前是 From 账户）
- 原始 `description` 匹配 `^(CONTRA|CLEAR|PAYMENT|RECEIVE|CLAIM|RATE) (FROM|TO) (.+)$` 这种"自动生成格式"：**当前账户是 To 账户 → 原样显示**；**当前账户是 From 账户 → 强制改写成 `"{TYPE} TO {to_account_code}"`**（不管原文写的方向词是什么）
- RATE 类型还有单独的 `"Transaction from/to X (Rate: n)"` 格式改写规则

AG 是这两笔 CONTRA 的 `from_account_id`，按上面第二条规则应该被改写成 `"CONTRA TO EXPENSES"`（`to_account_code` = EXPENSES 账户的 `account_id` 业务码）——这正是旧版截图显示的文本。

新版 `findDomainPaymentHistoryLines` 这一行（[TransactionHistoryMapper.xml:245](../mybatis/TransactionHistoryMapper.xml)）：`t.description AS description`，原样透传存库值，完全没做这层按视角改写的逻辑，所以 AG 看到的还是数据库里字面存的 `"CONTRA FROM AG"`。

**影响范围**：全租户——任何账户只要是 `CONTRA/CLEAR/PAYMENT/RECEIVE/CLAIM/RATE` 类型交易里的 From 方（或对方描述是自动生成格式），看自己的 Payment History 时描述文案的方向都会跟旧版反着显示。

### 修复涉及的文件（供以后动手时参考，这次未改动）

- `backend/src/main/resources/mybatis/TransactionHistoryMapper.xml`——`findDomainPaymentHistoryLines`（Bug 1/4）、`findDataCaptureHistoryLines`（Bug 3）、`aggregateDomainPaymentBfByAccount`（Bug 1 的 B/F 部分）
- `backend/src/main/resources/mybatis/TransactionSearchMapper.xml`——按 §15 建立的惯例，`manualCrDrTransactionTypes` 等片段是跟 History 那份故意重复维护的两份拷贝，如果 Search/List 页面有同样的 domain-fee 符号问题，这个文件要同步改（这次没有专门核对 Search 页，只核对了 Payment History）
- `backend/src/main/java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java`——排序（Bug 2，第 236-238 行）、ID PRODUCT 兜底链（Bug 3，第 329-330 行）、`domainProductFromDescription()`/`resolveDomainHistoryProduct()`（§21.1b，第 391-434 行——需要照抄旧版 `historyResolveDomainShareRoleLabel`/`historyResolveAutoRenewCommissionSourceCompany` 从 `remark` 标签重建产品名+描述，不能只是修字符串匹配的拼写）
- 改完后按 §15 的验证方式：`mvn compile` + 两个 mapper XML 单独做 XML 合法性校验（历史上这两个文件改动时都踩过注释里误写 `--` 的坑）+ 用 AG 这个账户手工核对每一行金额/描述/ID PRODUCT/排序跟旧版截图逐条比对

---

## 22. §21.1 的另一种解法：不改显示逻辑，改数据本身——已执行，过程中出过一次事故并已修复

用户不想照抄旧版那套"识别标签再反转符号"的显示层逻辑（理由：旧版那套是为了兼容 PHP 自己的写入习惯而存在的历史包袱，新版 `chargeDomainFee()` 的写入方式已经不一样了），问能不能保持新版现在这套简单的默认显示公式不变。核实后确认**这个方向是对的，而且比照抄旧版更合理**——细节如下。

### 22.1 根因：不是显示层缺例外，是新旧两版的写入方向本来就反了

直接读 [`DomainFeeChargeServiceImpl.java`](../../java/com/eazycount/service/impl/DomainFeeChargeServiceImpl.java) 的 `chargeDomainFee()`：
- Pay Domain Fee 行（139-140 行）：`buildPaymentLine(c168TenantId, payerAccountId, profitAccountId, ...)` → `account_id` = 付款方，`from_account_id` = C168
- Commission 行（165 行）：`buildPaymentLine(c168TenantId, profitAccountId, row.getAccountId(), ...)` → `account_id` = C168，`from_account_id` = 收佣金的人
- Net Profit 行（176 行）：`buildPaymentLine(c168TenantId, profitAccountId, profitAccountId, ...)` → `account_id`=`from_account_id`=C168 自己（自引用）

而全库排查（64 条带 `DOMAIN_LIST_FEE`/`DOMAIN_SHARE_COMMISSION`/`DOMAIN_NET_PROFIT`/`AUTO_RENEW*` 标签的 PAYMENT 交易，全部在 `tenant_id=77`）发现，**这批全部是迁移过来的旧数据（没有一条是 2026-08-27 备份之后新建的）**，写入方向是：
- Fee 行：`account_id`=C168（收款方），`from_account_id`=付款方——**跟新版代码正好相反**
- Commission 行：`account_id`=收佣金的人，`from_account_id`=C168——**跟新版代码正好相反**
- Net Profit 行：`account_id`=C168，`from_account_id`=`NULL`——不是自引用，形状也不一样

核对下来这个"反向"没有一个例外（Fee 行 `account_id` 100% 是 `4837`，Commission 行 `from_account_id` 100% 是 `4837`，Net Profit 行 `from_account_id` 100% 是 `NULL`）。

结论：新版 `chargeDomainFee()` 自己新建的交易，配合现在这套**没有任何标签例外的默认公式**（To 账户负、From 账户正、自引用为 0）天然就是对的——这也是用户测试时"感觉这个功能没问题"的原因，他测的是新建这条路径。截图里报错的，是迁移过来、按旧版写入习惯存的历史数据。照抄旧版那套显示层例外反而会把新建的交易也搞错（相当于对已经反过来的方向再反一次）。真正该做的是**只订正这批旧数据本身的 `account_id`/`from_account_id`，让它们符合新版自己的写入约定，完全不碰 `TransactionHistoryMapper.xml`**。

### 22.2 清单（执行前核实过的范围）

| 类别 | 条数 | 金额合计 | 需要的订正 |
|---|---|---|---|
| A. Pay Domain Fee / Auto Renew 扣费（`DOMAIN_LIST_FEE` ×10 + `AUTO_RENEW` 扣费 ×1） | 11 | 24,000 | 对调 `account_id`/`from_account_id` |
| B. Commission 佣金（`DOMAIN_SHARE_COMMISSION` ×40 + `AUTO_RENEW|COMMISSION` ×4） | 44 | 7,800 | 对调 `account_id`/`from_account_id` |
| C. Net Profit 净利润（`DOMAIN_NET_PROFIT` ×8 + `AUTO_RENEW|NET_PROFIT` ×1） | 9 | 11,880 | `from_account_id` 从 `NULL` 补成等于 `account_id`（自引用，对齐新版 `buildPaymentLine(profitAccountId, profitAccountId, ...)` 的写法） |

全部 64 条，均在 `tenant_id=77`（C168），无跨租户情况。

### 22.3 事故：第一版对调脚本在这台 MariaDB 上没有正确工作，55 条数据被写成了自引用——已发现并修复

第一版脚本用的是最直觉的写法：

```sql
UPDATE transactions
SET account_id = from_account_id,
    from_account_id = account_id
WHERE ...;
```

按 MySQL 官方文档，同一条 UPDATE 语句里多列赋值应该都读**这一行更新前的原始值**，这样写理论上能正确互换两列。但实际执行后发现完全不是这样——**这台服务器上，第二个赋值 `from_account_id = account_id` 读到的是同一语句里第一个赋值刚写入的新值，不是原始值**，导致两列最终被写成了同一个值（都变成了原来 `from_account_id` 的值），而不是互换。类别 A（11 条）和类别 B（44 条）全部中招，变成了自引用（`account_id = from_account_id`）。类别 C 因为只改了单独一列（`from_account_id = account_id`，没有互换），不受影响，一次执行就是对的。

**发现方式**：执行后没有直接相信"脚本跑完就结束"，照例逐行核对了 AG（`id=7515`）和 K（`id=17046`）这两条已知的具体例子，发现两条记录的 `account_id`/`from_account_id` 变成了同一个值，跟预期的"互换"结果对不上，才发现问题。

**能够安全修复的原因**：损坏后的数据虽然两列相同，但原始信息并没有真的丢——
- 类别 A：损坏后 `account_id` 恰好还是对的（本来就该改成付款方，而这正是损坏后两列共同的值），只需要把 `from_account_id` 单独修回 `4837` 即可
- 类别 B：同理，损坏后 `account_id` 恰好还是对的（`4837`），`from_account_id` 需要修回收佣金的人——这个信息没有丢，因为 `remark` 标签本身就带着 `AID:{account.id}`（比如 `[DOMAIN_SHARE_COMMISSION|MAC999|ROLE:SALES|AID:4841]` 里的 `4841`），用 `REGEXP_SUBSTR` 从 `remark` 里现取即可，不需要回滚

用一次性修复脚本（未留档，属于当场手工订正，逻辑等价于：Fee 行 `SET from_account_id=4837 WHERE account_id=from_account_id`；Commission 行 `SET from_account_id = CAST(REPLACE(REGEXP_SUBSTR(remark,'AID:[0-9]+'),'AID:','') AS UNSIGNED) WHERE account_id=from_account_id`）把这 55 条修复回正确状态，修复后逐条核对了全部 55 条（AG/K 两个已知例子 + 全量列表跟 §22.2 的清单一一核对），确认跟预期完全一致。

**已经把 [fix_domain_fee_commission_account_direction_swap.sql](fix_domain_fee_commission_account_direction_swap.sql) 改成了安全写法**（`UPDATE ... JOIN (SELECT ... 快照子查询) src ON ... SET t.col = src.col`，SET 读的是子查询快照而不是同一张表正在被改的行，不会再复现这个问题），脚本文件顶部加了醒目的坑位说明，避免以后有人照着最初那个直觉写法重写一遍。

### 22.4 执行结果

三个脚本（[fix_domain_fee_commission_account_direction_swap.sql](fix_domain_fee_commission_account_direction_swap.sql) 修复后的安全版本 + [fix_domain_net_profit_self_reference.sql](fix_domain_net_profit_self_reference.sql)）全部执行完毕并核对通过：

| 类别 | 条数 | 执行后状态 |
|---|---|---|
| A. Fee | 11 | `account_id`=付款方，`from_account_id`=C168（`4837`）——跟新版写入约定一致 |
| B. Commission | 44 | `account_id`=C168（`4837`），`from_account_id`=收佣金的人——跟新版写入约定一致 |
| C. Net Profit | 9 | `account_id`=`from_account_id`=C168（`4837`，自引用）——跟新版写入约定一致 |

全部由 Claude 直接连接本地 `count_real`（`127.0.0.1:3306`，root）执行。§21.1/§21.1b 提到的 CR/DR 符号、ID PRODUCT、描述文案问题（针对 domain fee/commission 这几类），现在应该已经用现有的默认公式（配合 §16/§17 已经实现的自引用归零规则）正确显示，**不需要再改 `TransactionHistoryMapper.xml`/`TransactionHistoryServiceImpl.java`**——建议下次登录时用 AG（Pay Domain Fee）和 K（Commission）这两个账户实际打开 Payment History 页面核对一遍。

### 22.5 遗留说明

- §21 清单里其余的 Bug 2（排序用 `created_at`）、Bug 3（Data Capture ID PRODUCT 兜底）、Bug 4（CONTRA/PAYMENT 描述按视角改写）**仍然是应用层代码缺口，没有被这次的数据订正解决**，因为它们跟 domain fee 写入方向无关，是独立的问题，还是需要按 §21 的清单去改代码。
- 这次事故也是一个提醒：以后任何"互换两列"的一次性订正脚本，优先用 `UPDATE ... JOIN (快照子查询) ...` 的写法，不要图省事用同一张表内联的 `SET a=b, b=a`，不同 MySQL/MariaDB 版本对这个写法的实际求值顺序不完全可靠。

### 22.6 §21.1b 剩下那半个问题（ID PRODUCT 空白）：也用数据订正解决，不改代码——已执行

金额符号订正完（§22.4）后，用户拿 K 账户实测确认金额已经对了，但 Commission 那一行 ID PRODUCT 还是空的（`-`）。排查后确认这是一个跟符号无关、完全独立的问题：`TransactionHistoryServiceImpl.domainProductFromDescription()`（391-416 行）靠字符串匹配 `description` 判断产品名，其中 `d.contains("COMMISSION")` 判断的是拼对了的 "COMMISSION"（两个 S），但这批旧数据的 `description` 存的是旧库沿用多年的错别字 "Commision"（一个 S），永远匹配不上，函数直接返回空。

用户明确要求：**不要照抄旧版那套从 `remark` 标签重建文案的逻辑，就按新版自己现在的方式来，而且不希望这批记录还带着 `remark`**——跟 §22 处理符号问题的思路一致：不改 `TransactionHistoryServiceImpl.java`，而是把这 64 条旧数据的 `description`/`remark` 直接订正成新版 `chargeDomainFee()` 自己新建交易时会产出的样子，让现有代码不用改就能读对。

**处理依据**：`DomainFeeChargeServiceImpl.java` 里 `buildPaymentLine()` 全程都是 `txn.setRemark(null)`（167、177 行调用处都不传 remark）——新版自己新建的这三类交易从来不带 `remark`，`[DOMAIN_LIST_FEE|...]` 这套标签本来就是旧版 PHP 自己的记账手段，新版不读也不写。新版对应的 `description` 精确文本：
- Commission（169-172 行）：`{SALES|CS|IT|PROFIT} COMMISSION FROM {payerCode}`
- Net Profit（180-181 行）：`NET PROFIT FROM {payerCode}`

**排查中顺带发现的一个旧库自身的 bug**：40 条 `DOMAIN_SHARE_COMMISSION`（不含 4 条 `AUTO_RENEW|COMMISSION`）的 `description` 全部硬编码写死成 `"... Commision for K"`——不管实际付费公司是 MAC999/TZX/WSMT/95/AG/RS/WCC/BP17/X17/23/UG 哪一个，文案里的公司代码永远是 `K`（K 是旧版后台处理这类分成的操作账号，不是付费公司）。真正的付费公司代码只留在 `remark` 标签里（比如 `[DOMAIN_SHARE_COMMISSION|AG|ROLE:SALES|AID:4841]` 里的 `AG`），所以订正描述文案时，是先从即将清空的 `remark` 里把 `ROLE:` 和付费公司代码取出来拼成新文案，再清空 `remark`，不是直接拿旧 `description` 改字。

**脚本**：[fix_domain_fee_commission_description_normalize.sql](fix_domain_fee_commission_description_normalize.sql)。范围：
- 44 条 Commission：`description` 重建成 `{ROLE} COMMISSION FROM {payer}`（如 `SALES COMMISSION FROM AJ`），`remark` 清空
- 9 条 Net Profit：`description` 重建成 `NET PROFIT FROM {payer}`（如 `NET PROFIT FROM AJ`），`remark` 清空
- 11 条 Fee（10 条 `DOMAIN_LIST_FEE` + 1 条 `AUTO_RENEW`）：`description` 不动——`DOMAIN_LIST_FEE` 那 10 条本来就是 `"Pay Domain Fee"`，转大写后跟 `domainProductFromDescription()` 的 `d.startsWith("PAY DOMAIN FEE")` 已经能匹配上，不需要改；`AUTO_RENEW` 那 1 条（`id=17044`，文案是 `"Renew AJ | 1 year"`）目前新版没有对应的写入路径可以照抄，先不编一个文案出来，只清空 `remark`，ID PRODUCT 会继续显示空白，留作已知的小缺口。这 11 条只清空 `remark`。

执行前用只读查询把 `role`/`payer` 的提取结果核对过一遍（44+9 条全部正确，没有解析失败的），执行后逐条核对了新 `description` 文本和 `remark` 是否清空，抽查的 10 条（含 AG 的 Fee 行、K 的 Commission 行、AJ 相关的 4 条 Commission + 1 条 Net Profit）全部符合预期。全部 64 条确认 `remark` 已清空。

**结果**：现有的 `domainProductFromDescription()` 代码不用改，K 这行现在 `description = "SALES COMMISSION FROM AJ"`，转大写后能命中 `contains("COMMISSION")`，ID PRODUCT 会显示 `COMMISSION`。同样逻辑覆盖了另外 43 条 Commission 和 9 条 Net Profit（Net Profit 的 `NET PROFIT FROM ...` 命中 `d.startsWith("NET PROFIT")` 分支，显示 `PROFIT`）。

### 22.7 意外的额外收获：`description` 订正顺带激活了一段已有但一直没生效过的"C168 只看 Net Profit"过滤

用户核对时发现，`C168 (EZAY COUNT)` 自己的 Payment History——旧版会看到 BP17 那 3 条 Commission 记录混在里面（`+480`/`+120`/`+120`），新版看不到，只剩 Net Profit 那几行，怀疑是不是数据又出问题了。

排查后确认**新版是对的，不用改**：`TransactionHistoryServiceImpl.buildDomainPaymentHistorySlice()`（132-153 行）里早就写了一段专门给 `C168`/`PROFIT` 账户用的过滤——`c168ProfitView` 为真时，只保留 `description` 以 `"NET PROFIT"` 开头的行，其余（Fee、Commission）一律跳过；命中的 Net Profit 行还会强制把 `signedAmount` 设成 `+amount`（不走 mapper 里自引用归零那条规则）。这段代码不是这次改的，是早就存在的既有设计。

但在 §22.6 订正 `description` 之前，这批旧数据的 Net Profit 行存的是 `"Profit By K"`，根本不以 `"NET PROFIT"` 开头，`isNetProfitDescription()` 一直判不中——也就是说，**订正之前，C168 自己的 Payment History 里这 9 条 Net Profit 其实一条都不会显示**（不是显示错，是压根被这段过滤挡在外面），Commission 那几条本来就会被挡住。§22.6 把 `description` 改成 `"NET PROFIT FROM {payer}"` 之后，正好让这段一直没生效过的过滤逻辑生效了：Net Profit 正确显示出来，Commission 继续被挡住——这是新版原本就该有的行为，不是这次改动引入的新逻辑，纯粹是数据格式对齐后"激活"了已有代码。

旧版会显示 Commission，是因为旧版 PHP 没有这层"C168 只看 Net Profit"的过滤，只要 C168 是交易任意一方（收 Fee 或付 Commission）都会显示——这属于旧版自己的展示口径，不是新版要对齐的目标。用户确认："旧版那三条 Commission 记录是不对的（不该显示），当前新版才是对的"。

---

## 23. §21.4 结论修正：`CONTRA`/`PAYMENT`/`CLEAR`/`CLAIM` 的按视角改写逻辑其实早就写好了，缺的是数据格式——已执行，全库 10,567 条

用户拿 AG 的 CONTRA 复现 §21.4 提到的问题（当前查看账户是 `from_account_id` 时，应该显示 `TO` 开头的改写文案，实际显示的是存库原文），并且自己在别的公司新建了一笔手动 CONTRA 测试，发现新建的这笔从 `from_account_id` 视角看**是对的**——这个反馈直接推翻了 §21.4 当时"显示层缺这段逻辑"的结论，逼着重新查了一遍代码。

### 23.1 真正的根因：改写逻辑已经存在，只是被一个格式门槛挡住了

`TransactionHistoryServiceImpl.applyManualTransferHistoryPresentation()`（594-619 行）就是要的那段按视角改写逻辑，本身完全没问题：`from_account_id` 一方看到 `"{TYPE} TO {收款方}"`，`account_id` 一方看到 `"{TYPE} FROM {付款方}"`。

但它前面有一道门槛（`shouldRewriteManualTransferHistoryDescription()`，625-636 行）：

```java
return upper.startsWith(typeToken + " FROM ") && upper.contains(" TO ");
```

**必须同时满足"以 `TYPE FROM ` 开头"和"文本里包含 ` TO `" 才会触发改写。**

新版手动提交交易时（`TransactionSubmitServiceImpl.formatTransferDescription()`，407-410 行）写入的是一次性双边格式：`"CONTRA FROM {付款方} TO {收款方}"`，天然能过这道门槛，所以用户新建的那笔测试数据显示是对的。而**这批迁移过来的旧数据 `description` 只存了单边**：`"CONTRA FROM AG"`，没有 " TO " 这段，门槛过不去，改写逻辑被跳过，存库原文直接透传。

跟 §22 的 domain fee 问题是同一种模式（新旧两版的写入约定不一样，显示层只认新版格式）——不是代码缺失，是数据格式跟现在的门槛判断对不上。

### 23.2 排查范围：不只 C168，全库几乎所有手动交易都是这个格式

按 `PAYMENT`/`CLAIM`/`CLEAR`/`CONTRA` 四种类型、`description` 匹配"`{TYPE} FROM %` 但不含 ` TO `"扫了全库（不限 `tenant_id`）：

| 租户 ID | 受影响条数 |
|---|---|
| 77（C168） | 9 |
| 78 | 1,976 |
| 79 | 1,711 |
| 80 | 191 |
| 81 | 3,953 |
| 82 | 150 |
| 83 | 361 |
| 84 | 531 |
| 85 | 560 |
| 89 | 1,122 |
| 94 | 3 |
| **合计** | **10,567** |

执行前确认过：这 10,567 条 `account_id`/`from_account_id` 都非空，跟 `account` 表 JOIN 全部能对上（无孤儿引用）；`account_id`=收款方（To）、`from_account_id`=付款方（From）这个方向本身没有问题（核对过 `TransactionSubmitServiceImpl.submitTransfer()`，新版新建交易也是同样的方向，不是像 §22 domain fee 那样两个字段反过来），只需要订正 `description` 本身，不用动 `account_id`/`from_account_id`。`RATE` 类型不在这批里——已经在 §15/§16/§17 单独处理过，有自己独立的双边格式和改写逻辑。

### 23.3 修复

脚本：[fix_manual_transfer_description_two_sided_format.sql](fix_manual_transfer_description_two_sided_format.sql)。用 `UPDATE ... JOIN (快照子查询)` 的安全写法（吸取 §22.3 那次事故的教训），把 `description` 重写成 `"{TYPE} FROM {付款方代码} TO {收款方代码}"`（代码来自 `account_id`/`from_account_id` 关联出的 `account.account_id`）——具体文字内容不影响正确性，因为一旦通过门槛判断，显示层会按查看账户重新拼一遍最终文案，这里只需要让它"看起来是双边格式"即可。

**执行结果**：一次性影响 10,567 条，全部成功，0.42 秒完成。执行后核对：全库不再有匹配"单边旧格式"条件的行；AG 那两笔 CONTRA（`id=14124`/`14126`）确认变成 `"CONTRA FROM AG TO EXPENSES"`；额外抽查了 78/81 租户的几条 `PAYMENT`/`CONTRA`/`CLEAR`，格式都正确。幂等（`description NOT LIKE '% TO %'` 这个门槛订正后自然不再匹配），可安全重跑。

---

## 24. §21 清单的 Bug 2 + 附带的 B/F 日期问题：已改代码（不是数据订正）

这两处是这次唯一的**应用层代码改动**（§22/§23 都是数据订正，没碰代码），改在 [`TransactionHistoryServiceImpl.java`](../../java/com/eazycount/service/impl/TransactionHistoryServiceImpl.java)。

### 24.1 Bug 2：History 合并排序改成按 `transaction_date`

第 236-239 行，原来只按 `createdAt`（数据录入时间）+ `id` 排序，改成：

```java
lines.sort(Comparator
        .comparing(TransactionHistoryLineRow::getTransactionDate, Comparator.nullsLast(LocalDate::compareTo))
        .thenComparing(TransactionHistoryLineRow::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
        .thenComparing(TransactionHistoryLineRow::getId, Comparator.nullsLast(Integer::compareTo)));
```

主排序键换成 `transactionDate`（业务发生日期，对齐旧版行为），原来的 `createdAt`/`id` 降级成同一天内的 tie-break，没有丢弃。

### 24.2 B/F 行日期固定显示字面量 `"B/F"`

第 261 行，原来是 `bfRow.setDate(formatHistoryDate(dateFrom))`（显示查询范围起始日，比如 `01/01/2026`），改成：

```java
bfRow.setDate("B/F");
```

跟旧版一致，固定显示 `"B/F"` 字样，不随查询范围变化。`dateFrom` 这个方法参数在 `range.setFrom(...)` 那里还在用，不是废弃参数。

### 24.3 验证情况

这个环境没有装 Maven（`mvn`/`mvnw` 都找不到），没能实际跑 `mvn compile`——两处改动都手工核对过类型正确（`getTransactionDate()` 返回 `LocalDate`，文件顶部已 import；`setDate` 接收 `String`，`"B/F"` 合法字面量），但建议用户在自己本地环境跑一次 `mvn compile` 确认。

### 24.4 §21 清单剩余项（已在 §25 处理完）

Bug 3（Data Capture ID PRODUCT 兜底到 `id_product_main`/`id_product_sub`）当时还没处理，见下方 §25——最终也是数据订正，不是代码改动。

---

## 25. Bug 3：`data_capture_line.id_product` 全库回填——已执行，59,615 行

### 25.1 先确认新版自己的写入路径没有这个问题

`DataCaptureSummaryServiceImpl.java` 第 482-484 行，提交明细行时强制校验：

```java
if (trimToNull(line.getIdProduct()) == null) {
    throw new BusinessException("Product Id is required for every line");
}
```

GAME、BANK 两个 category 走的是同一段校验，没有分支——只要是走新版这条提交链路，`id_product` 保证非空。确认这纯粹是继承自旧库的历史数据质量问题（`data_capture_details.id_product` 在旧库本身就没有稳定写入过，`id_product_main`/`id_product_sub` 倒是一直有值），不是新版代码需要按 GAME/BANK 分别处理的问题，跟用户的判断一致。

### 25.2 排查范围

全库 `data_capture_line` 75,234 行，**59,615 行（约 79%）`id_product` 为空**，且确认这 59,615 行没有一条是 `id_product_main`/`id_product_sub` 也同时为空的（兜底链能完全覆盖，不会有漏网之鱼）。按公司（tenant）拆分：

| 公司 | 该公司总明细行数 | `id_product` 为空 | 占比 |
|---|---|---|---|
| 95 | 27,338 | 21,745 | 79.5% |
| AG | 26,179 | 20,294 | 77.5% |
| RS | 15,463 | 12,351 | 79.9% |
| WCC | 1,732 | 1,732 | 100% |
| MAC999 | 1,885 | 1,357 | 72.0% |
| WSMT | 1,141 | 870 | 76.2% |
| TZX | 930 | 718 | 77.2% |
| VG | 525 | 525 | 100% |
| C168 | 16 | 12 | 75.0% |
| CX | 15 | 11 | 73.3% |
| **合计** | **75,234** | **59,615** | **79.2%** |

WCC、VG 两家是 100%——这两家所有 Data Capture 记录 `id_product` 一条不落全是空的。没列出来的公司（BK1/M1/M2 等）说明该公司数据全部正常，不受影响。

### 25.3 修复：直接回填 DB，不改代码

脚本：[fix_data_capture_line_id_product_backfill.sql](fix_data_capture_line_id_product_backfill.sql)。用跟旧版 `history_api.php` 读取侧一样的兜底规则回填：`product_type='SUB'` 且 `id_product_sub` 非空 → 用 `id_product_sub`；否则用 `id_product_main`。回填完之后，现有的 `TransactionHistoryServiceImpl`（`dcl.id_product AS idProduct`，读到空才兜底显示 `"DATA CAPTURE"`）不用改一行代码，自然就能读对。

**执行结果**：一次性影响 59,615 行，3.18 秒完成。执行后核对：全库不再有 `id_product` 为空的行；AG 已知的几条（`transaction_id=42741/42743/51354/51356/66324/66326`）确认变成 `"HONG MING SOON"`/`"LEW ZHEN CHENG"`，跟 `id_product_main` 一致；按公司逐一核对空值数也全部归零。幂等（`id_product IS NULL OR id_product=''` 这个门槛回填后自然不再匹配），可安全重跑。

至此 §21 清单里发现的问题（Bug 1/1b/2/3/4 + B/F 日期）全部处理完毕——Bug 2 和 B/F 是代码改动（§24），其余全部是数据订正（§22/§23/§25），没有再动 `TransactionHistoryMapper.xml`/`TransactionHistoryServiceImpl.java` 里跟符号、描述、ID PRODUCT 相关的判断逻辑。

---

## 26. 补漏：§22.6 当时漏判断的 `AUTO_RENEW` 那 1 条 Fee 行——已修复

用户拿 `AJ`（AH JI）账户核对（`C168` 公司下、`account_id=5514`），发现 ID PRODUCT 空白、`description` 还是原始的 `"Renew AJ | 1 year"`，跟另外 10 条 `DOMAIN_LIST_FEE`（显示 `"Pay Domain Fee"`/`PAYMENT`）不一致。

§22.6 当时的判断是错的：以为"新版没有专门给续费扣费写文案的代码路径，所以这条没法对齐"。重新查证：

- 新版 `DomainFeeChargeServiceImpl.chargeDomainFee()` 是**唯一**的扣费入口，不管是普通域名费还是续费触发的扣费，写的都是同一个字面量 `"PAY DOMAIN FEE"`（第 143-144 行）——新版压根不区分"域名费"和"续费扣费"这两种场景，不存在"没有对应代码路径"这回事。
- 旧版这边独立地也走到了同一个结论：`history_api.php` 判断"是不是域名费类交易"时，`historyIsAutoRenewFeeSms()` 本来就会让 `[AUTO_RENEW|...]` 标签命中跟 `[DOMAIN_LIST_FEE|...]` 同一条 `isDomainListFee` 分支，命中后不管原始 `description` 是什么，一律强制显示成 `"Pay Domain Fee"`——这正是用户截图里旧版显示 `"PAY DOMAIN FEE"` 的原因，尽管这条底层存的原文其实是 `"Renew AJ | 1 year"`。

**处理**：`id=17044` 的 `description` 订正成 `"PAY DOMAIN FEE"`，跟另外 10 条 Fee 行一致——现有的 `domainProductFromDescription()` 会命中 `d.startsWith("PAY DOMAIN FEE")`，ID PRODUCT 显示 `PAYMENT`，不用改代码。[fix_domain_fee_commission_description_normalize.sql](fix_domain_fee_commission_description_normalize.sql) 补了第 4 条语句覆盖这条（幂等，`WHERE description='Renew AJ | 1 year'` 保证只影响还没修的状态），同时更新了脚本顶部的说明，去掉了之前"这条先不处理"的过时结论。

至此 domain fee 相关的 65 条（64 条 + 这条 `id=17044` 的补充修复）全部对齐新版格式。

---

## 27. 新发现：C168 账户在 Payment History 和 Search/List 页面余额对不上——**改动已撤销，仅作排查过程存档**

> ⚠️ 本节记录的四处代码改动（`TransactionHistoryServiceImpl.java`/`TransactionHistoryDao.java`/`TransactionHistoryMapper.xml`/`TransactionSearchMapper.xml`）用户后来想清楚后要求**全部退回**，已经逐处还原干净（用 `grep` 核对过 `c168ProfitView`/`excludeFeeCommission`/`domainFeeOrCommissionDescription`/`c168NetProfitDescription`/`isDomainFeeOrCommissionDescription` 这些改动引入的标识符，四个源码文件里只剩 `c168ProfitView` 这一个改动前就存在的原始变量，其余全部清除）。撤销原因用户没有展开说，只说"想了想不应该这么做"——**这个方向以后要不要做需要重新跟用户确认，不能直接按下面记录的方案再做一次**。以下内容仅保留当时的排查过程和技术分析存档，不代表当前代码状态。

用户拿 C168 账户核对，发现 Payment History 页面显示余额 13,560.00，Search/List（Contra Inbox 汇总表格）页面同一个账户显示 17,400.00，两边对不上。

### 27.1 排查：两边算的是不同口径

手算验证：C168 的 Fee（`from_account_id`=C168，From 方 +25,200）− Commission（`account_id`=C168，To 方 −7,800）+ Net Profit（自引用抵消为 0）= **17,400**，跟 Search 页显示的数字分毫不差。

- **Payment History（13,560）**：`TransactionHistoryServiceImpl.buildDomainPaymentHistorySlice()` 里专门给 `C168`/`PROFIT` 账户写的 `c168ProfitView` 过滤——只保留 `description` 以 `"NET PROFIT"` 开头的行，Fee、Commission 全部滤掉，代表"留存净利润"。
- **Search/List（17,400）**：`TransactionSearchMapper.xml` 的 `aggregateDomainPaymentCrDr`，完全没有任何 C168 专属处理，Fee/Commission 都被当成普通交易正常计入，Net Profit 因为是自己转自己（`account_id`=`from_account_id`=C168），在两条 `UNION ALL` 分支里分别贡献 `-amount`/`+amount`，互相抵消成 0，完全不体现。

### 27.2 用户确认的业务规则

"C168 账户只算 Net Profit 这个业务判断，还有手动交易记录，Fee/Commission 这种不包含。"——即需要一套排除规则：排除 Fee、排除 Commission，保留 Net Profit（且要显示留存的实际金额，不是自引用抵消后的 0），保留任何未来可能出现的普通手动交易（不能像原来那样用"白名单只认 Net Profit"的方式，会连带误伤手动交易——核实过 C168 目前确实没有任何非 domain-fee 的手动交易，但规则本身要写对，不能只对付当前数据）。

### 27.3 修改的文件

- **`TransactionHistoryServiceImpl.java`**：`buildDomainPaymentHistorySlice()` 的过滤从"白名单只保留 NET PROFIT"改成"黑名单排除 Fee/Commission"（新增 `isDomainFeeOrCommissionDescription()`），Net Profit 命中时才做"显示留存金额而不是自引用 0"的覆盖。
- **`TransactionHistoryMapper.xml`**：`aggregateDomainPaymentBfByAccount`（B/F 期初余额）新增 `excludeFeeCommission` 参数（由 Java 传入 `c168ProfitView`），排除 Fee/Commission，Net Profit 用同样的"一边计满、一边记 0"处理，避免自引用互相抵消。
- **`TransactionSearchMapper.xml`**：`aggregateDomainPaymentCrDr`（之前完全没有 C168 专属逻辑，是这次的主要缺口）加了同样的排除 + Net Profit 修正，两条 `UNION ALL` 分支都改了。
- **`TransactionHistoryDao.java`**：`aggregateDomainPaymentBfByAccount` 方法签名加了 `excludeFeeCommission` 参数（只有一处调用方，已同步改）。

两个 mapper 各自维护了一份 `domainFeeOrCommissionDescription`（Search 这边多一份 `c168NetProfitDescription`）SQL 片段，跟 §15 建立的"故意不共享、两份手动同步"惯例一致。

### 27.4 验证

改完直接手写等效 SQL 跑了一遍（不是只信代码逻辑）：C168 在 `aggregateDomainPaymentCrDr` 口径下的 `crDrAmount` 从 17,400.00 变成 **13,560.00**，跟 Payment History 页面完全一致。AG 等其他账户的计算路径没有被这次改动触碰（排除条件只在 `account.account_id IN ('C168','PROFIT')` 时才生效）。

同样没有 Maven 环境跑 `mvn compile`，建议用户本地编译确认。

## 22. 事后修复：Add Domain 报 500（`NullPointerException: ... "c168Tenant" is null`），根因是查 C168 租户时硬编码了 `owner_id = 1`

**现象**：Domain 页面点 Add Domain 提交后前端报"An unexpected error occurred"，后端日志：

```
NullPointerException: Cannot invoke "com.eazycount.entity.Tenant.getId()" because "c168Tenant" is null
```

**根因**：`count_real` 库里核实过，`tenant` 表 `code='C168'` 那一行实际 `owner_id=3`，而这个库里根本没有 `id=1` 的 owner（`owner` 表最小 id 是 3）：

```sql
SELECT id, code, owner_id FROM tenant WHERE code='C168';  -- id=77, owner_id=3
SELECT id FROM owner ORDER BY id;                          -- 最小是 3，没有 1
```

但 [DomainServiceImpl.java](../../java/com/eazycount/service/impl/DomainServiceImpl.java) 里 `createDomain`/`updateDomain`/`deleteAllTenants` 三处、以及 [DomainFeeChargeServiceImpl.java:98](../../java/com/eazycount/service/impl/DomainFeeChargeServiceImpl.java) 都写死了 `domainDao.findTenantByCodeAndOwnerId("C168", 1)`——这个 `1` 是早年在 `testcount` 手工测试库里 C168 恰好挂在 `owner_id=1` 下留下的硬编码假设，迁移到 `count_real` 后这个假设不成立，查询直接查不到行返回 `null`。`createDomain` 里对返回值没做判空就直接 `c168Tenant.getId()`，于是空指针；`DomainFeeChargeServiceImpl` 那处虽然判了空但因此永远抛 `BusinessException("C168 ledger tenant not found")`，功能上同样是坏的。

`AutoRenewServiceImpl.java:109` 早就用的是不依赖 owner 的 `tenantDao.findTenantByCode("C168")`（`TenantDao`/`TenantMapper.xml` 里现成的方法）——C168 在 `tenant` 表里本来就是全局唯一一行（不按 owner 区分），按 owner 过滤本身就是多余且错误的前提。

**修复**：把上述四处硬编码 `domainDao.findTenantByCodeAndOwnerId("C168", 1)` 统一换成 `tenantDao.findTenantByCode("C168")`（`DomainServiceImpl`/`DomainFeeChargeServiceImpl` 新增注入 `TenantDao`），并给 `createDomain` 补上之前缺失的判空（查不到就抛 `BusinessException("C168 ledger tenant not found")`，跟 `DomainFeeChargeServiceImpl` 保持一致，不再直接 NPE）。

涉及文件：
- `backend/src/main/java/com/eazycount/service/impl/DomainServiceImpl.java`
- `backend/src/main/java/com/eazycount/service/impl/DomainFeeChargeServiceImpl.java`

验证：`mvnw compile` 通过；未在 UI 上回归测试 Add/Update/Delete Domain 三个入口，建议重启后端后手工过一遍。

**影响范围**：`DomainDao.findTenantByCodeAndOwnerId(code, ownerId)` 本身没问题（`updateDomain` 里按真实 `groupCode`/`companyCode` + `ownerId` 查询的用法是对的，没动），问题只出在这四处把 `ownerId` 写死成字面量 `1` 去查 C168。如果以后 `count_real` 里 owner 表结构再变（比如真的建了 id=1 的 owner），也不会影响这个修复，因为改成按 `code` 全局查，不再依赖任何具体的 owner id。

---

> **编号提醒**：上面这节"事后修复：Add Domain 报 500"被标成了 `## 22`，跟本文档前面已有的 §22（"§21.1 的另一种解法……"）重复——看起来是另一个会话/进程往这份文档追加内容时没同步到最新编号。这里不去改动别人刚写的内容，只是标注一下：接下来这节延续的是 §1-§27 那条主线的编号，叫 **§28**，不是接在这节"Add Domain"后面的 §23。以后要清理编号的话，两节内容都要保留，只是数字需要重新理一遍。

---

## 28. 推翻 §21/§27 里"MAC999/TZX/WSMT 是备份之后才补进生产环境"的猜测——真相是旧版前端的虚拟兜底显示，从来没有真实数据——已回填

用户不认可"备份之后才补的"这个猜测，坚持认为旧库应该本来就有数据，要求重新排查。用更宽泛的方式（不再局限于精确标签匹配，搜 `description`/`sms` 里任何提到 `MAC999`/`TZX`/`WSMT` 的记录）重新翻了一遍 `c168_net_legacy_20260827`，确认这三家**不管用什么搜索条件，都翻不出一条 `DOMAIN_NET_PROFIT` 记录**——不是筛选条件太严格漏看，是真的没有。

### 28.1 真正的根因：旧版前端自己承认这是"虚拟"数据

`history_api.php` 里有个函数名字就叫 **`buildVirtualDomainNetProfitHistory()`**（962 行），逻辑是：先按 `[DOMAIN_NET_PROFIT|...]` 标签查真实记录，**查不到（`empty($rows)`）的话，现场用同一天该公司的 Fee 减 Commission 算一个数字，拼成一条看起来像真实交易的行塞进显示结果**——注释原话："若真实利润单未落库，则与交易页一致：动态按 Fee - Commission 兜底显示"。这条**从来没有真正写进 `transactions` 表**，只是页面渲染时凭空生成的，旧库备份、当前生产库大概率都一样没有真实存储——用户在 `count168.com` 上看到的 "NET PROFIT FROM MAC999" 就是这套虚拟兜底算出来的，不是数据库里存的。

§21/§27 当时的猜测（"备份之后才手动补进生产环境"）是错的——不存在"补数据"这回事，旧版从一开始就没往数据库写过这一行，是显示层现算的。

### 28.2 处理：把旧版的虚拟计算结果，当成真实数据回填

新版 `chargeDomainFee()` 每次扣费只要利润 > 0 就**一定**会真实写入 Net Profit 记录（`DomainFeeChargeServiceImpl.java:175-178`），不存在"没写就现算"这种兜底机制——所以不是去新版代码里补一套"虚拟计算"逻辑，而是把这三条按新版自己的写入方式，当成真实数据回填进去，这样新版反而比旧版更完整、更一致。

脚本：[fix_domain_net_profit_backfill_mac999_tzx_wsmt.sql](fix_domain_net_profit_backfill_mac999_tzx_wsmt.sql)。金额用旧版同一套算法（Fee 2400 − Commission 720 = 1680，跟其他公司的 Net Profit 金额规律完全一致）；`account_id`=`from_account_id`=4837（C168 自己，自引用，跟 §22 订正后其余 9 条 Net Profit 的形状一致）；`transaction_date`/`created_by`/`approved_by`/`created_at`/`approved_at` 沿用各自 Fee 批次里同一批次的值（`id=7269`/`7274`/`7279`），当成是在补完那次历史批次本来就该有的一步，不是编造成"现在"发生的。

**执行结果**：插入 3 条（`id=93762/93763/93764`），全部核对通过：
- `NET PROFIT FROM MAC999`：1,680，日期 2026-04-22，`created_by=JACKSEE`（跟 MAC999 那笔 Fee 一致）
- `NET PROFIT FROM TZX`：1,680，日期 2026-04-22，`created_by=K`
- `NET PROFIT FROM WSMT`：1,680，日期 2026-04-22，`created_by=JACKSEE`

至此 C168 名下除了 BP17（没有 Fee 记录，旧库本身如此）和 X17（Fee/Commission/Profit 三者对不上账，§27 已经记录、需要用户自己核实）之外，其余 10 家公司的 Fee/Commission/Net Profit 三条链路全部完整、金额自洽。
