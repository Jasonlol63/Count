# Process Copy From

Games Process 的 Add Process 弹窗有一个 Copy From 下拉，用来快速复制一个已存在 process 的全部配置
（含 formula）建一个新 process。

> 删除 process / 删除 account / 解绑 account-tenant 这三处「Transaction 数据完整性」相关的删除防护，
> 原本记在这份文档里，现已单独移到
> [`frontend-springboot-migration.md` 第 37 节](./frontend-springboot-migration.md#37-process--account-删除防护--transaction-amount-完整性专项2026-08-26)，
> 这份文档现在只记录 Copy From 本身的功能。

## 目标行为

Add Process 时选择「Copy From」某个已有 process：

- 复制该 process 的 currency、remove word、replace word from/to、remark、description、schedule(day use)
  等全部配置。
- 新 process 的 code（Process ID）必须由用户手动输入一个新的、唯一的值。
- 保存成功后，新 process 还会连带复制源 process 名下所有的 formula（可对复制出来的 formula 做完整 CRUD）。
- **编辑 formula 双向同步**：源 process（A）和复制出来的 process（B）的 formula 建立后，
  在任一边编辑（`source_percent`/`input_method`/`formula`/`description`/`account_id`），另一边对应
  的那条也会自动跟着改；删除不同步，B 删自己的只影响 B，不碰 A，反之亦然。详见下方
  [「Formula 双向同步」](#formula-双向同步2026-08-26)。
- 删除这个新 process 时直接删，不会影响源 process 的任何数据——两者建立后就是完全独立的行。

## 数据库改动

新增 `process.copied_from_process_id`（nullable，`ON DELETE SET NULL`），仅作**追溯/排查用**，
不参与任何业务逻辑判断：

- [`backend/src/main/resources/sql/add_process_copied_from_process_id.sql`](../backend/src/main/resources/sql/add_process_copied_from_process_id.sql) —— 增量 migration。
- [`backend/src/main/resources/sql/schema.sql`](../backend/src/main/resources/sql/schema.sql) —— 同步更新新装库的建表语句。

```sql
ALTER TABLE `process`
    ADD COLUMN `copied_from_process_id` INT UNSIGNED DEFAULT NULL
        COMMENT '来源 process.id（Copy From 建立时记录，仅用于追溯/排查，不影响业务逻辑）'
        AFTER `code`,
    ADD KEY `idx_process_copied_from` (`copied_from_process_id`),
    ADD CONSTRAINT `fk_process_copied_from`
        FOREIGN KEY (`copied_from_process_id`) REFERENCES `process` (`id`)
        ON DELETE SET NULL;
```

`ON DELETE SET NULL` 是关键：源 process 以后被删掉时，新 process 不受牵连——既不会被级联删除，
也不会因为 RESTRICT 挡住源 process 的删除。

为什么不需要更多字段：Copy From 只是创建那一刻的一次性深拷贝，复制完成后两个 process 之间
除了这一个可选的追溯字段外，没有任何数据库层面的强关联，天然满足"删除互不影响"的要求。

## 后端实现

涉及文件：

- `backend/src/main/java/com/eazycount/entity/Process.java` —— 加 `copiedFromProcessId` 字段。
- `backend/src/main/java/com/eazycount/dto/ProcessDTO.java` —— 加 `copyFromProcessId` 入参（仅 add-process 用）。
- `backend/src/main/java/com/eazycount/dao/ProcessDao.java` + `backend/src/main/resources/mybatis/ProcessMapper.xml`
- `backend/src/main/java/com/eazycount/service/impl/ProcessServiceImpl.java`（核心逻辑）

`ProcessMapper.xml` 新增三条 `INSERT ... SELECT`，一次性把源 process 的子数据深拷贝到新 process id 下
（不走"先查出来再逐条 insert"，避免中间态、也更快）：

```sql
<!-- process_description_link -->
INSERT INTO process_description_link (process_id, description_id, created_at)
SELECT #{newProcessId}, description_id, NOW()
FROM process_description_link WHERE process_id = #{sourceProcessId}

<!-- process_day -->
INSERT INTO process_day (process_id, day_of_week)
SELECT #{newProcessId}, day_of_week
FROM process_day WHERE process_id = #{sourceProcessId}

<!-- data_capture_formula -->
INSERT INTO data_capture_formula (tenant_id, process_id, product_type, id_product, ...)
SELECT tenant_id, #{newProcessId}, product_type, id_product, ...
FROM data_capture_formula WHERE process_id = #{sourceProcessId} AND tenant_id = #{tenantId}
```

`ProcessServiceImpl.addNewProcess` 里拆成两个 private 方法，逻辑上自成一体：

```java
Process copySource = resolveCopyFromSource(processDTO.getCopyFromProcessId(), processDTO.getTenantId());
// ... 用 copySource 的 category/currency/removeWord/replaceWord/remark 覆盖请求体里同名字段
// ... insert 新 process，copiedFromProcessId = copySource.getId()
if (copySource != null) {
    copyProcessChildData(copySource.getId(), process.getId(), processDTO.getTenantId(), sessionUser.login_id);
}
```

- `resolveCopyFromSource`：按 `(id, tenantId)` **重新从 DB 权威读取**源 process，不信任前端已经
  预填好的表单值，避免竞态/篡改；找不到直接抛 `Copy From source process not found!`。
- `copyProcessChildData`：调用上面三条 `INSERT ... SELECT`，失败统一包装成 `BusinessException`。
- 新 code 依然走原有唯一性校验（`findProcessCodeByTenantId`），如果用户手滑输入了跟源 process 一样的
  code 会被当成重复 code 直接拒绝，天然满足"必须输入新名字"的要求，不需要额外写判断。

## Formula 双向同步（2026-08-26）

**需求**：A 是原始 process，B 是从 A Copy From 出来的 process。两边各自拥有一份独立的
`data_capture_formula` 行。要求：
- **编辑双向同步**：在 B 改了某条 formula 的可编辑字段，A 对应那条也要跟着改；反过来 A 改了，
  B 也要跟着改。
- **删除完全不同步**：B 删自己的 formula 只影响 B 这一条，不碰 A；反过来也一样。

**关联方式：`formula_group_id`（分组标签，不是外键）**

- [`backend/src/main/resources/sql/add_data_capture_formula_group_id.sql`](../backend/src/main/resources/sql/add_data_capture_formula_group_id.sql) —— 在 `data_capture_formula` 加一列
  `formula_group_id INT UNSIGNED DEFAULT NULL`，只加索引，**不建外键**。
- [`backend/src/main/resources/sql/schema.sql`](../backend/src/main/resources/sql/schema.sql) —— 同步更新新装库的建表语句。

```sql
ALTER TABLE `data_capture_formula`
    ADD COLUMN `formula_group_id` INT UNSIGNED DEFAULT NULL
        COMMENT 'Copy From 同步分组标签，非外键；同组的 formula 编辑时互相同步，删除不连带'
        AFTER `id`,
    ADD KEY `idx_data_capture_formula_group_id` (`formula_group_id`);
```

**为什么不用外键、而是纯标签字段**：如果做成"指向源 formula id"的外键，源那条被删掉时要嘛挡住删除
（不符合"删除互不影响"），要嘛级联把剩下几条的关联字段清空导致互相失联。用一个不挂外键、纯粹当分组
标签的整数字段，不管组里谁被删掉，剩下的行手上的标签值都不变，同步关系不受影响。

**建组时机（Copy From 深拷贝阶段）**：`ProcessServiceImpl.copyProcessChildData` 里，插入 formula
之前先调用 `ProcessDao.backfillFormulaGroupIds`：

```sql
<!-- 只处理还没有分组标签的源 formula：第一次被复制时，用自己的 id 当分组标签自我标记 -->
UPDATE data_capture_formula
SET formula_group_id = id
WHERE process_id = #{sourceProcessId}
  AND tenant_id = #{tenantId}
  AND formula_group_id IS NULL
```

然后 `copyProcessFormulas` 的 `INSERT ... SELECT` 把 `formula_group_id` 也原样带过去给新 process 的
formula 行。这样不管链条多长（A→B、B 再被复制成 C……），只要是同一份 formula 传下来的，全部共享同一个
`formula_group_id`，一次查询就能找到整个"家族"，不需要递归查找。已经带过分组标签的源 formula（比如
A 本身也是别人复制来的）不会被这条 `UPDATE` 覆盖，直接原样继承。

**编辑时同步（`MaintenanceServiceImpl.updateFormulaMaintenance`）**：先照常更新被编辑的那一行，
再查它的 `formula_group_id`，非空的话追加一条 `UPDATE` 把同样的字段镜像写到组内其它所有行：

```java
int updated = maintenanceDao.updateFormulaMaintenanceRow(
        tenantId, id, accountId, sourcePercent, inputMethod, formula, description, updatedBy);
// ...
Integer groupId = maintenanceDao.findFormulaGroupIdByIdAndTenantId(tenantId, id);
if (groupId != null) {
    maintenanceDao.propagateFormulaGroupUpdate(
            tenantId, groupId, id, accountId, sourcePercent, inputMethod, formula, description, updatedBy);
}
```

同步的字段跟这个页面本来能编辑的字段完全一致：`source_percent`/`input_method`/`formula`/
`description`/`account_id`；`process_id`/`tenant_id`/`created_by`/`created_at` 各行各自保留不变。
被联动改的那几行，`updated_by`/`updated_at` 也会写成本次实际操作人/当前时间（内容确实变了，
如实记录）。

**删除不同步（`MaintenanceServiceImpl.deleteFormulaMaintenance`）**：完全没改，还是只删被选中的那些
id，`formula_group_id` 只是标签，删除时不会连坐组内其它行。

**历史数据不回填**：这次改动只影响之后新发生的 Copy From；改动生效前已经建立的 A/B 组合，它们的
formula 行没有 `formula_group_id`，不会自动开始同步（用户已确认不需要回填）。

## 删除逻辑不需要改

`data_capture_formula.process_id → process.id` 已确认是 `ON DELETE CASCADE`（在 `testcount` 库里核实过），
删除一个 Copy From 出来的新 process 会自动带走它自己名下复制出来的 formula 行，源 process 完全不受影响，
`ProcessServiceImpl.deleteProcessById` 不需要为 Copy From 场景单独写代码。

（这条跟「删除防护」是两回事：删除防护是不管有没有 Copy From 都通用的 transaction 数据保护，
见 [`frontend-springboot-migration.md` 第 37 节](./frontend-springboot-migration.md#37-process--account-删除防护--transaction-amount-完整性专项2026-08-26)。）

## 涉及文件汇总

- `backend/src/main/resources/sql/add_process_copied_from_process_id.sql`（新增 migration）
- `backend/src/main/resources/sql/add_data_capture_formula_group_id.sql`（新增 migration，formula 同步）
- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/java/com/eazycount/entity/Process.java`
- `backend/src/main/java/com/eazycount/entity/DataCaptureFormula.java`
- `backend/src/main/java/com/eazycount/dto/ProcessDTO.java`
- `backend/src/main/java/com/eazycount/dao/ProcessDao.java`
- `backend/src/main/java/com/eazycount/dao/MaintenanceDao.java`
- `backend/src/main/resources/mybatis/ProcessMapper.xml`
- `backend/src/main/resources/mybatis/MaintenanceMapper.xml`
- `backend/src/main/java/com/eazycount/service/impl/ProcessServiceImpl.java`
- `backend/src/main/java/com/eazycount/service/impl/MaintenanceServiceImpl.java`

前端改动记录在 `Count-frontend` 仓库的 `docs/process-copy-from-frontend-changes.md`。
