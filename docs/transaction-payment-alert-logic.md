# Payment Alert（Transaction 页面账户爆红提醒）

> 范围：Account 的 Payment Alert 设置（`payment_alert` / `alert_day` / `alert_amount` / `alert_specific_date`）如何在 `POST /api/transaction/search` 的返回结果里驱动 Transaction 页面账户行的红色高亮。
>
> 本功能是从旧版 PHP（`count168/api/transactions/search_api.php`）**逐行复刻**到 Spring Boot 的，字段命名、判定顺序、边界行为都尽量对齐旧版，移植时发现的行为差异见第 5 节。

---

## 1. 功能概述

Account 的 Edit Account 弹窗里有一个 "Payment" 区块：

| UI 字段 | 后端字段（`account` 表 / `User`/`UserListDTO`） | 说明 |
|---|---|---|
| Payment Alert（Yes/No） | `payment_alert`（0/1） | 总开关 |
| Alert Type | `alert_day`（历史遗留列名，实际存的是 alert 类型） | `weekly` / `monthly` / `"1"`~`"31"`（字符串数字，代表天数） |
| Start Date | `alert_specific_date`（历史遗留列名，实际存的是起算日期） | 判定的起算基准日 |
| Alert (Amount) | `alert_amount` | **必须是负数**，UI 输入正数会被前端自动转负 |
| Remark | `remark` | 纯展示，不参与判定 |

当账户满足条件时，Transaction 页面（`TransactionPaymentPage.jsx` 及其嵌入的 Contra Inbox 等子视图，只要复用了 `TransactionTablesSection`）会把该账户当日那一行整行标红（CSS class `transaction-alert-row`）。

---

## 2. 判定算法（与旧版 `search_api.php` 完全一致）

以查询/查看的**结束日期 `date_to`** 作为"当前检查日"，**不是**服务器真实的系统当前日期——这样翻历史区间也能还原出"当时"应该是什么颜色。

依次判定：

1. **左表（`balance >= 0`）永远不判定**，直接不变色。
2. `payment_alert != 1` 直接不变色。
3. **金额条件**：`alert_amount` 必须 `< 0`，且 `balance <= alert_amount` 时满足（例如阈值 -100，balance 到 -100 或更负才算超标）。
4. **时间条件**（`alert_day` + `alert_specific_date` 都要有值）：
   - 若 `start_date > date_to`（起算日还没到），不满足；
   - `daysDiff = date_to - start_date` 的天数差；
   - `alert_day == "weekly"`：`daysDiff % 7 == 0` 时满足（起算日当天 daysDiff=0 也算，之后每 7 天再触发一次）；
   - `alert_day == "monthly"`：`date_to` 的"日"（day-of-month）与 `start_date` 的"日"相同时满足——**纯数字比较，不处理大小月边界**（例如 start_date=1月31日，2月/4月等没有31号的月份永远不会触发，3月31日才会再次触发）；
   - `alert_day` 是数字字符串（`"1"`~`"31"`）：记为 `N`，`daysDiff % N == 0` 时满足。**`N=1` 意味着每天都满足**（任何整数 mod 1 恒为 0），效果等价于"从起算日起只要金额条件还成立就一直亮着"，**不是**"只在起算日当天亮 1 天"。
5. 金额条件与时间条件**同时**满足才变色。

> **关键认知（第 5 节详述）**：这里的 "Alert Type / N" 是**循环触发频率**（每隔 N 天重新判一次），不是"触发后维持 N 天然后自动熄灭"的时长窗口。这是旧版本身的设计，我们是原样复刻，不是移植引入的偏差。

---

## 3. Spring Boot 实现

| 层级 | 文件 | 改动 |
|---|---|---|
| DTO | [`dto/TransactionSearchResult.java`](../backend/src/main/java/com/eazycount/dto/TransactionSearchResult.java) | `Row` 新增 `boolean alertActive` 字段（对应旧版 `is_alert`） |
| Service | [`service/impl/TransactionSearchServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/TransactionSearchServiceImpl.java) | `mergeSearchSlices(...)` 新增 `dateTo` 参数；批量拉取该 tenant 下所有账户的 4 个 alert 字段（`userDao.findUserByTenantId(tenantId)`），按 `accountDbId` 建 map；每行算完 `balance` 后调用新增的私有方法 `computeIsAlert(balance, account, dateTo)` 得到 `alertActive` |

`computeIsAlert(...)` 是对第 2 节算法的直译，用 `java.time.LocalDate` + `ChronoUnit.DAYS` 算 `daysDiff`，`alertSpecificDate`（`java.util.Date`）通过 `Instant.ofEpochMilli(...).atZone(ZoneId.systemDefault()).toLocalDate()` 转成 `LocalDate`（避免对 `java.sql.Date` 调 `toInstant()` 抛异常）。

**字段命名踩坑**：最初命名为 `isAlert`，Lombok 给 boolean 字段 `isAlert` 生成的 getter 就是 `isAlert()`；但 Jackson 序列化时会把这个 getter 名字的 `is` 前缀**再剥一次**，导致实际吐出的 JSON key 变成 `"alert"` 而不是 `"isAlert"`（用 Jackson 实测验证过）。改名成不以 `is` 开头的 `alertActive` 后，Lombok 生成 `isAlertActive()`，Jackson 剥掉 `is` 前缀后精确还原回 `alertActive`，天然对齐，不需要 `@JsonProperty` 兜底。

---

## 4. 前端接线

- [`Count-frontend/src/pages/transaction/lib/transactionSearchNormalize.js`](../../Count-frontend/src/pages/transaction/lib/transactionSearchNormalize.js) 第 36 行：`is_alert: row.alertActive ? 1 : 0`（原来是硬编码的 `is_alert: 0`，这是新版一直不生效的直接原因）。
- [`Count-frontend/src/pages/transaction/components/TransactionTablesSection.jsx`](../../Count-frontend/src/pages/transaction/components/TransactionTablesSection.jsx) 读取 `row.is_alert == 1` 加 CSS class `transaction-alert-row`，这部分逻辑本来就是对的，未改动。
- 样式：`Count-frontend/public/css/transaction.css` 里 `.transaction-alert-row`（红底白字加粗），未改动。

---

## 5. 移植过程中额外发现并修复的 bug（与 Transaction 端算法无关，属于 Account 保存链路）

调试真实账户（A3）时发现 Payment Alert 设置完全没有存进数据库（`payment_alert=0`、其余字段全 `NULL`），根因是 **Account 主列表页的 Edit Account 弹窗从未对 Alert Amount 做"自动转负"**：

- [`Count-frontend/src/pages/account/accountLogic.js`](../../Count-frontend/src/pages/account/accountLogic.js) 的 `normalizeAlertAmount(value)`（正数自动转负，如 `100 → "-100"`）只在 Bank Process 列表页的账户编辑入口（`useBankProcessListPage.js`）里被调用；
- Account 主列表页（`AccountListPage.jsx` → `buildAccountUpdateRequest`/`buildAccountCreateRequest`，定义在 [`accountListApi.js`](../../Count-frontend/src/pages/account/accountListApi.js)）虽然 `import` 了这个函数，但从未调用，是个死引用——金额会原样（正数）传给后端。
- 而 `computeIsAlert` 明确要求 `alert_amount < 0` 才算满足金额条件（照抄旧版约定），正数永远不触发。

**修复**：把 `normalizeAlertAmount` 的调用收进共享的 `buildAccountCreateRequest`（`accountListApi.js`），`create`/`update` 两个请求构建函数共用这一处，改一次两边生效。对已经自己转过负的 Bank Process 页面调用不会重复出错——负数再过一遍 `normalizeAlertAmount` 结果不变（幂等）。

---

## 6. 已知语义差异（未处理，等待产品决策）

用户最初的需求描述是"提示时长通过 Alert Type 维持"，暗示的是**触发后维持/显示 N 天，之后自动熄灭**的"时长窗口"语义。

但旧版 `search_api.php` 实际实现、以及我们原样复刻的算法，是**循环触发频率**语义：只要金额条件持续满足，`weekly`/`monthly`/`N` 只决定"隔多久重新判一次"，`N=1` 时任何一天都满足周期条件，效果等同于"从起算日起只要还欠着钱就一直亮着"，而不是"亮 1 天就熄灭"。

两种语义需要的实现完全不同：
- **频率模式**（现状）：无需记录"上次何时触发过"，纯粹用 `daysDiff % N` 计算，天然支持历史区间回放。
- **时长模式**（用户原始预期）：需要额外定义"一个触发窗口从哪天算到哪天"，并且要处理"金额条件在窗口内时断时续"要不要提前熄灭等边界，旧版完全没有这部分实现，需要重新设计规则。

目前维持现状（频率模式，与旧版行为一致），是否要改成时长模式，需要用户/产品明确判定规则后再实现。
