# Data Capture Summary：删除 Sub 行后 sub_order 留洞的问题

## 问题现象

- Summary 页面里，一个 Id Product 下可以有多条 Sub 行，按 `sub_order` 排序显示（1、2、3……）。
- 删除中间某一条 Sub 行后，界面上看起来会自动重排成连续编号（前端 `resequenceSubOrdersInRows` 本地重排，只影响当前这次渲染），但数据库 `data_capture_formula.sub_order` 并没有被改写——比如原本 1/2/3，删掉编号 2 之后，数据库里剩下的两行仍然是 1 和 3，留了一个洞。
- 旧版 PHP 前端在删除后会额外调用一个接口把重排结果同步回数据库；这次 Spring Boot 重写后，这一步被漏掉了，`deleteFormulas()` 只做了单纯的 DELETE，没有任何收尾。

## 为什么这个洞是真实风险，不只是好看不好看

`DataCaptureSummaryServiceImpl.resolveExistingForUpdate()` 自带的注释写明：Bank 页面的 UI 经常在刷新后丢失某一行的 `templateId`（即 `id`），这时会 fallback 用业务键 `parentIdProduct + accountId + subOrder` 去数据库里反查这一行（`findByBusinessKey`）。

如果数据库里的 `sub_order` 因为之前的删除留了洞，跟前端当下认为的编号对不上，这次 fallback 查找就会落空，报 `Formula not found`。也就是说这不是纯 UI 瑕疵，而是一个在特定条件下（刷新丢 id）会真正触发的查找失败场景。

## 本次后端改动

把"删除后补洞"这件事收敛到删除动作本身，后端在同一个事务里自动完成，前端不需要新增任何调用。

### 改动的方法

`DataCaptureSummaryServiceImpl.deleteFormulas()`：

- 删除每一项时，额外记录被删的行是不是 `SUB` 类型、以及它的 `parentIdProduct`（用一个 `Set<String>` 去重，一次请求可能同时删多个 Id Product 下的行）。
- 所有删除都执行完之后，针对每个被动过的 `parentIdProduct`，调用新增的私有方法 `resequenceSubOrders(tenantId, processId, parentIdProduct)`：
  1. 按当前 `sub_order` 升序查出这个 Id Product 下剩余的所有 Sub 行（新增 DAO 方法 `findSubRowsOrderedBySubOrder`）。
  2. 依次赋值 1、2、3……，只对编号真的变化了的行才发 UPDATE（新增 DAO 方法 `updateSubOrderById`，只改 `sub_order` 一个字段，不动 `formula`/`description`/`updated_by` 等其他字段，避免误伤）。

### 新增的方法

- [`DataCaptureSummaryDao.java`](../backend/src/main/java/com/eazycount/dao/DataCaptureSummaryDao.java)
  - `findSubRowsOrderedBySubOrder(tenantId, processId, parentIdProduct)`
  - `updateSubOrderById(id, tenantId, subOrder)`
- [`DataCaptureSummaryMapper.xml`](../backend/src/main/resources/mybatis/DataCaptureSummaryMapper.xml) — 对应两条 SQL（`SELECT ... ORDER BY sub_order ASC, id ASC` / `UPDATE ... SET sub_order = ...`）。
- [`DataCaptureSummaryServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/DataCaptureSummaryServiceImpl.java) — `deleteFormulas()` 收尾逻辑 + 新增私有方法 `resequenceSubOrders()`。

### 为什么新增 Sub 行不需要处理

`saveAsSub()` 新增 Sub 行时，`sub_order` 一直是后端自己算的 `findMaxSubOrder(...) + 1`（永远追加在末尾），不会产生空洞，所以只有删除路径需要补这一步。

## 前端没有改动

前端现有的本地重排逻辑（`resequenceSubOrdersInRows` / `resequenceAllSubOrders`）原样保留——它本来就是给用户"删完立刻看到干净编号"的乐观 UI，本身没有问题。这次后端把数据库补齐之后，前端这段代码显示的编号会和数据库里真实存的编号一致，从"好看但是假的"变成"好看而且是真的"，不需要新增或删除任何前端代码。

## 验证

- `./mvnw -q -o compile` 通过（本机默认 `JAVA_HOME` 是 JDK 11，需要临时指到 JDK 21 才能编译，运行环境本身的 JDK 版本未受此次改动影响）。
- 建议手工验证：找一个 Id Product 下有 3 条 Sub 数据的场景，删除中间一条，直接查 `data_capture_formula` 表确认剩余两行的 `sub_order` 变成连续的 1、2，而不是留着 1、3 的洞。

## 影响范围

- 只影响 `deleteFormulas()` 这一条路径，`saveAddFormula` / `updateFormula` / `submit` 均未改动。
- 新增的两个 DAO 方法只在 `resequenceSubOrders()` 里被调用，不影响其他任何现有查询。
