# Bank Process Status 编辑锁定规则

`OFFICIAL` / `E_INVOICE` / `BLOCK` 状态下的 Bank Process **禁止编辑**任何字段（day_start、day_end、frequency、contract、supplier/customer/company price、insurance、SOP、Remark、Profit Sharing 等）；仅 Status 本身仍可透过 Status 控件切换。`INACTIVE` 不受影响，维持可自由编辑。修改相关字段编辑入口时，**同步更新本文档**。

**锁点在「保存」而非「打开」**：用户仍可正常点击 Edit / Remark 图标打开对应弹窗，查看该 process 目前的完整信息（字段本身未做 disabled 处理）；真正的拦截发生在点击 Edit Process 弹窗的 **Update Process** / Remark 弹窗的 **Save** 时。

相关：[`accounting-due-frequency-rules.md`](./accounting-due-frequency-rules.md)（同三个状态对出账行为的影响）

## 范围

| 状态 | 可编辑 |
|------|--------|
| `ACTIVE` | 可以 |
| `INACTIVE` | 可以（不受本次改动影响） |
| `OFFICIAL` | 否 |
| `E_INVOICE` | 否 |
| `BLOCK` | 否 |
| `WAITING` | 未使用（后端不会手动设置此状态） |

Status 本身的切换（ACTIVE ↔ INACTIVE ↔ OFFICIAL ↔ E_INVOICE ↔ BLOCK）**不受此锁定影响**，走独立的 `POST /api/bank-process/update-status`，不经过下方的编辑守门。

## 后端实现

`BankProcessServiceImpl.java`：

- `EDIT_LOCKED_STATUS = {OFFICIAL, E_INVOICE, BLOCK}`
- `assertEditable(BankProcess existing)`：若 `existing.getStatus()` 在锁定集合内，抛出 `BusinessException`，不写入任何字段。
- 挂载点（守门放在拿到 `existing` 之后、写入前）：
  - 私有方法 `updateBankProcess`（被 `updateBankProcessDetails` / `POST /api/bank-process/update` 调用）——覆盖整包更新：day_start、day_end、frequency、contract、price（buy/sell/company）、insurance、SOP、Remark，以及同一事务内接着执行的 Profit Sharing 重建（`deleteBankProcessShareBatch` + `insertProfitSharing`）。因为整个方法在 `@Transactional` 内，`assertEditable` 一旦抛错，Profit Sharing 也不会被写入。
  - `updateBankProcessRemark`（`POST /api/bank-process/update-remark`）——列表上的快速备注编辑，是独立于主表单的 API 入口，需单独守门，否则可绕过主表单的锁定。

## 前端实现

`Count-frontend/src/pages/bankprocesslist/`：

- `hooks/useBankProcessListPage.js` 的 `openEdit(rowId)`：**不做拦截**，正常抓 row、拉账户列表、`setForm` / `setModalOpen(true)`，让用户看到该 process 的完整详情。
- `lib/bankProcessHelpers.js` 的 `bankProcessListRowToEditForm`：额外把 `row.issue_flag` 带进 `form.issue_flag`，供提交时判断。
- `hooks/useBankProcessListPage.js` 的 `submitForm`：在最前面（`guardWrite()` 之后）检查 `editMode && form.issue_flag ∈ {official, e_invoice, block}`，命中则 toast 提示并 `return`，不发起更新请求。
- `hooks/useBankProcessListPage.js` 的 `saveRemarkModal`：同样在发请求前检查 `remarkRow.issue_flag`，命中则 toast 提示并 `return`。
- `BankProcessListPage.jsx` 的 `openRemarkModal`：**不做拦截**，正常打开备注弹窗供查看。
- SOP 编辑是 Edit Process 弹窗内的子模态（`bankProcessTextModals.jsx`），没有独立 API，跟随主表单一起提交，由 `submitForm` 的检查挡住。

## 国际化 / 错误提示

`translateFile/pages/bankProcessTranslate.js`：

- 新增 key `errEditLockedByStatus`（en / zh 均有），供前端拦截时直接使用。
- `translateDynamicApiMessage` 新增正则匹配：消息含 `cannot be edited`（英文）或 `不可编辑`（中文）时，映射到 `errEditLockedByStatus`。用于万一前端检查被绕过、请求打到后端并被 `assertEditable` 拒绝时，toast 仍能显示正确翻译文案，而不是原始英文错误信息。

## 已知限制

- 后端守门以 Bank Process **当前**状态为准（请求发出时的 `existing.getStatus()`）。若用户打开 Edit Process 弹窗时状态本是可编辑的，但提交前另一个操作把该 process 状态改成锁定状态，前端 `form.issue_flag` 是打开当下的快照、不会跟着变，提交时前端检查可能放行，但后端 `assertEditable` 仍会正确拒绝（不会误放行），toast 会显示对应错误。
- 反之，弹窗打开时 `form.issue_flag` 已是锁定状态，前端检查会在用户点击 Update Process / Save 时先行拦截，不会发出请求；这是本次改动的主要行为。
