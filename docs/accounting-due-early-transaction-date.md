# Accounting Due：提前交易日期（Early Transaction Date）

Bank Process 的 Accounting Due 弹窗新增了一个"提前交易"功能：用户可以选择今天~今年年底之间的
任意一天，预览"如果时间走到那一天"会有哪些账单到期，并可以直接对这些提前出现的账单执行入账，
不需要真的等到那一天。

前端设计/交互细节见 `Count-frontend` 仓库的
[`docs/accounting-due-early-transaction-date.md`](../../Count-frontend/docs/accounting-due-early-transaction-date.md)。
这份文档只记录后端部分。

## 背景：`asOf` 参数早就存在，只是从没做成正式功能

[`AccountingDueInboxRequest.java`](../backend/src/main/java/com/eazycount/dto/AccountingDueInboxRequest.java)
的 `asOf` 字段（`LocalDate`，覆盖 `resolveInbox` 计算"今天"用的基准日）、
[`BkProcessAccountingDueController.java`](../backend/src/main/java/com/eazycount/controller/BkProcessAccountingDueController.java)
的 `/inbox` 接口透传、[`BankAccountingDueServiceImpl.resolveInbox`](../backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java)
里 `LocalDate today = asOf != null ? asOf : LocalDate.now();` 这条链路，在这次改动之前**就已经全部打通**——
只是原本的注释写的是"for dev/testing"，从没设计成给真实用户在 UI 上用的参数，前端也只有一个写死
`null` 的调试常量 `ACCOUNTING_DUE_AS_OF_OVERRIDE`。

这次改动本质上是把这条已有的开发者后门，正式升级成一个用户可用的功能，后端只补了一处校验。

## 唯一的后端改动：给 `asOf` 加范围校验

[`BankAccountingDueServiceImpl.resolveInbox`](../backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java)：

```java
LocalDate systemToday = LocalDate.now();
if (asOf != null) {
    LocalDate yearEnd = systemToday.withMonth(12).withDayOfMonth(31);
    if (asOf.isBefore(systemToday) || asOf.isAfter(yearEnd)) {
        throw new BusinessException("asOf must be between today and the end of the current year!");
    }
}
LocalDate today = asOf != null ? asOf : systemToday;
```

**为什么要加**：前端日历组件本身已经把可选范围限制在"今天~今年年底"，正常使用不会传出范围外的
值。但 `asOf` 原本是给内部调试用的，接口本身从来没有做过任何范围检查——如果有人绕过前端 UI
直接调用 `/api/bank-process/accounting-due/inbox`（比如用 Postman，或者以后又有别的调用方），
传一个离谱的未来日期（例如 10 年后），会带来两个问题：

1. `resolveFirstOfMonthDues` / `resolveMonthlyDues` 等方法是按月份从 `dayStart` 循环到 `asOf` 的，
   `asOf` 越离谱循环次数越多，对租户下所有 `bank_process` 逐个跑一遍是不必要的性能负担。
2. 业务逻辑上等于可以一次性把好几年份的账单全部解锁提前入账，跟"提前交易最多到今年年底"的产品
   设计意图不符。

这是一处防御性加固，不是功能必需——只有登录且有写权限的用户才能调这个接口，风险本身不高，但加上
之后成本很低。

顺带把 [`AccountingDueInboxRequest.java`](../backend/src/main/java/com/eazycount/dto/AccountingDueInboxRequest.java)
上那条过时的 "for dev/testing" 注释更新了，反映它现在是正式功能的一部分。

## 已确认的既有行为（未改动，容易被误以为要改）

排查这个功能时确认过几条既有逻辑，特意记录下来避免以后重复排查：

- **Once 频率会被提前交易影响**：`resolveOnceDue(dto, bp, today)` 判断 `today.isBefore(dayStart)`，
  这里的 `today` 就是 `asOf`。如果一个 Once 流程的 `dayStart` 是未来某天，正常情况下不会出现在
  Accounting Due 里；但只要把预览日期拉到 `dayStart` 或之后，它就会提前出现。这跟 Monthly / 1st of
  Every Month / Week / Day 是同一套判断逻辑，**没有被特殊排除**（用户已确认这是预期行为，不需要改）。
- **Compensation（1+N 合同的补偿账单）完全不受影响**：`resolveOnePlusCompensationDue(dto, tenantId)`
  这个调用**不接收 `today`/`asOf` 参数**，只看流程当前 `status` 是否为 OFFICIAL/E-Invoice/Block 且
  合同是 1+1/1+2/1+3 来决定要不要生成，跟预览日期完全无关。入账时 `postOneAccountingDuePeriod` 对
  COMPENSATION 类型固定用 `LocalDate.now()`（真实当天），同样不受 `asOf` 影响。
- **Transaction 入账逻辑完全不需要改**：`postToTransaction` 用的是每一行账单自己算出来的
  `postedDate`/`billing_period_start/end`，不是"今天"，所以提前预览出来的账单直接勾选入账，走的
  是和正常到期入账完全一样的代码路径。
- **幂等性有保障**：`bank_process_accounting_posted` 表 `(tenant_id, bank_process_id, posted_date,
  period_type)` 唯一键已经防止同一账期被重复过账，提前入账后，等真正到期日那天系统不会重复生成。

## 涉及文件

- [`backend/src/main/java/com/eazycount/dto/AccountingDueInboxRequest.java`](../backend/src/main/java/com/eazycount/dto/AccountingDueInboxRequest.java) —— 注释更新。
- [`backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java) —— `resolveInbox` 加范围校验。
