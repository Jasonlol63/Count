# Auto Renew — per-row "Charge" toggle on Approve

> 范围：在 Auto Renew 页面的每一行（仅 pending 可编辑行）新增一个开关，控制点击 Approve 时是否要生成 Domain Fee + Commission 流水，语义与 Domain 页面 Company/Group Settings 弹窗里的 "收费开关"（`chargeDomainFeeOnConfirm`）一致，但这里是**逐行独立**的，不是租户级全局设置。

---

## 1. 需求背景

Domain 页面的 Company/Group Settings 弹窗里已经有一个开关：保存设置时是否要立即对该租户收一次 Domain Fee（`CompanySettingsModal.jsx` 的 `chargeOnSave`）。

Auto Renew 页面此前审批（Approve）时**无条件**收费——只要点 Approve，就一定会调用 `DomainFeeChargeService.chargeDomainFee(...)` 生成 Domain Fee + Commission 两笔交易。用户希望在 Auto Renew 页面也能选择"只延长到期日、不产生付款"（例如白名单续期场景）。

要求：这个开关**放在每一行**（不是弹窗、也不是页面级全局开关），批准时按当前行的开关状态决定要不要收费。

---

## 2. 后端改动

复用的是 Domain 页面同一套收费服务 `DomainFeeChargeService`，approve 流程本身没变，只是给它加了一个"是否执行"的开关。

| 文件 | 改动 |
|---|---|
| [`backend/src/main/java/com/eazycount/dto/AutoRenewApprovalRequest.java`](../backend/src/main/java/com/eazycount/dto/AutoRenewApprovalRequest.java) | 新增 `Boolean chargeOnApprove`（JSON 字段 `charge_on_approve`）。`reject` 接口共用这个 DTO 但不传该字段，不受影响。 |
| [`backend/src/main/java/com/eazycount/controller/AutoRenewController.java`](../backend/src/main/java/com/eazycount/controller/AutoRenewController.java) | `approve` 接口读取该字段，**未传时默认为 `true`**（保持旧行为向后兼容），再传给 Service 层。 |
| [`backend/src/main/java/com/eazycount/service/AutoRenewService.java`](../backend/src/main/java/com/eazycount/service/AutoRenewService.java) / [`service/impl/AutoRenewServiceImpl.java`](../backend/src/main/java/com/eazycount/service/impl/AutoRenewServiceImpl.java) | `approveRequest(Integer requestId, String period)` → `approveRequest(Integer requestId, String period, boolean chargeOnApprove)`。原来无条件调用的 `domainFeeChargeService.chargeDomainFee(tenant, period)` 改成：<br>`chargeOnApprove ? domainFeeChargeService.chargeDomainFee(tenant, period) : Collections.emptyList()`<br>其余逻辑不变：价格仍然会查（`domainListFeePriceDao.findPriceByTenantTypeAndPeriod`，没配置价格照样报错拦住），到期日照样延长（`autoRenewDao.updateTenantExpiration`），审批记录照样落库（`autoRenewDao.approveRequest(...)`，`price` 字段存的是"应付价格"，即使没收费也会记录这个数值用于展示）。 |

**关掉开关时的行为**：只延长 `tenant.expiration_date`、把请求标记为已审批，**不生成 Transaction、不插入 `insertRequestTransactionLink`**，也就是完全不留付款记录。

---

## 3. 前端改动

| 文件 | 改动 |
|---|---|
| [`Count-frontend/src/pages/autorenew/AutoRenewPage.jsx`](../../Count-frontend/src/pages/autorenew/AutoRenewPage.jsx) | 在表头 Period 和 Status 之间插入一列 "Charge"；表格行里仅 `isPendingEditable` 的行渲染开关（非 pending 行显示 `—`）。开关 UI 直接复用 Domain 页面 `CompanySettingsModal.jsx` 里同款的 `company-share-charge-on-save` / `company-share-charge-switch` 样式类，视觉上和 Domain 页面保持一致。开关状态写入 `rowDrafts[requestId].chargeOnApprove`，`updateDraft(row.request_id, { chargeOnApprove: e.target.checked })`。确认批准弹窗的文案（`confirmApprove` / `confirmApproveNoCharge`）会根据这一行当前的开关状态动态切换，明确告知"会/不会创建付款"。 |
| [`Count-frontend/src/pages/autorenew/autoRenewPageHelpers.js`](../../Count-frontend/src/pages/autorenew/autoRenewPageHelpers.js) | `getRowDraftValues(row, drafts)` 返回值新增 `chargeOnApprove: draft.chargeOnApprove ?? true`（默认开，与后端默认值保持一致）。 |
| [`Count-frontend/src/pages/autorenew/autoRenewLogic.js`](../../Count-frontend/src/pages/autorenew/autoRenewLogic.js) | `approveAutoRenew({ requestId, period, chargeOnApprove = true })` 请求体新增 `charge_on_approve` 字段，POST 到 `api/auto-renew/approve`。 |
| [`Count-frontend/src/translateFile/pages/autoRenewTranslate.js`](../../Count-frontend/src/translateFile/pages/autoRenewTranslate.js) | 新增中英文案：`colCharge`（列头 "Charge"/"收费"）、`on`/`off`、`chargeToggleAria`、`confirmApproveNoCharge`（关闭收费时的确认弹窗文案）。 |
| [`Count-frontend/public/css/auto_renew.css`](../../Count-frontend/public/css/auto_renew.css) | 表格 grid 布局从 8 列（无 Submitter）/ 9 列（有 Submitter）扩为 9 列 / 10 列，在所有响应式断点（默认桌面、≤1280px、1025–1440px 13寸覆盖、≤1024 平板，含中英文两套宽度）同步插入新增列的宽度定义；同时把 `--auto-renew-table-min-width` 及各断点 `min-width: max(100%, …)` 的横向滚动阈值都相应调大，给新列留出空间。 |

---

## 4. 兼容性

- 后端 `charge_on_approve` 字段未传时按 `true` 处理，任何还在用旧请求体的调用方（理论上不存在，因为只有这一个前端在调）行为不变。
- 数据库结构无改动，完全复用现有 `Transaction` / `tenant_auto_renew` 表；关闭收费时只是不写入交易记录，不是新增字段去标记"是否已收费"——如果之后要在已批准列表里回显某一行当时有没有收费，需要额外加字段记录，目前没有做。

---

## 5. 验证情况

- `./mvnw.cmd -o compile`：后端编译通过。
- `npx vite build`：前端构建两轮均无编译/语法错误。
- **未做真实登录环境下的端到端浏览器验证**（该环境没有跑起来的带数据库鉴权会话），列宽在真实数据下的视觉效果、窄屏换行情况建议本地起服务后实测一遍。
