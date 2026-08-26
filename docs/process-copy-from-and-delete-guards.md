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

## 删除逻辑不需要改

`data_capture_formula.process_id → process.id` 已确认是 `ON DELETE CASCADE`（在 `testcount` 库里核实过），
删除一个 Copy From 出来的新 process 会自动带走它自己名下复制出来的 formula 行，源 process 完全不受影响，
`ProcessServiceImpl.deleteProcessById` 不需要为 Copy From 场景单独写代码。

（这条跟「删除防护」是两回事：删除防护是不管有没有 Copy From 都通用的 transaction 数据保护，
见 [`frontend-springboot-migration.md` 第 37 节](./frontend-springboot-migration.md#37-process--account-删除防护--transaction-amount-完整性专项2026-08-26)。）

## 涉及文件汇总

- `backend/src/main/resources/sql/add_process_copied_from_process_id.sql`（新增 migration）
- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/java/com/eazycount/entity/Process.java`
- `backend/src/main/java/com/eazycount/dto/ProcessDTO.java`
- `backend/src/main/java/com/eazycount/dao/ProcessDao.java`
- `backend/src/main/resources/mybatis/ProcessMapper.xml`
- `backend/src/main/java/com/eazycount/service/impl/ProcessServiceImpl.java`

前端改动记录在 `Count-frontend` 仓库的 `docs/process-copy-from-frontend-changes.md`。
