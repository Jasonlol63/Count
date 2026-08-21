# Group 模式下 Report 入口消失问题

## 问题现象

- 以 Owner 或 Admin 身份登录，切换到 Group 模式（选中 Group ID，例如 "OK"，Company 栏为空）。
- 刚从 Company 切换到 Group 时，侧边栏 Report 入口会短暂出现。
- 但只要在 Group 模式下切换到任意其它页面（例如 Admin → Account），Report 入口就会自动消失。
- 与用户的角色（Owner / Admin）、权限无关，纯粹跟 Group/Company 的选中状态有关。

## 根因

Report 入口的显隐由 [`canShowReportInSidebar`](../../Count-frontend/src/utils/auth/sidebarPermissions.js) 判断，其中有一条"纯 Group 模式直接放行"的快捷路径：

```js
const filter = readPersistedDashboardGcFilter();
if (filter.groupOnly && filter.selectedGroup) return true;
```

而 `filter.groupOnly` 由 [`readPersistedDashboardGcFilter`](../../Count-frontend/src/utils/company/sharedCompanyFilter.js) 计算：

```js
const savedCompanyId = readDashboardSelectedCompanyId();   // sessionStorage: dashboard_selected_company_id
const groupOnly = isDashboardGroupOnlyMode() && savedCompanyId == null;  // sessionStorage: dashboard_group_only
```

问题在于 `dashboard_group_only` 和 `dashboard_selected_company_id` 是两个各自独立写入 sessionStorage 的 key。切换到 Group 模式时调用的是 `persistDashboardGroupOnlyMode(true)`，但这个函数**只设置了 `dashboard_group_only`，从未清掉 `dashboard_selected_company_id`**。

- 从 Company 页签直接点 Group 切换时，恰好走的是同时清空两个 key 的调用路径（如 `persistDashboardFilterState`），所以 Report 会正常出现。
- 但页面间跳转（Admin → Account 等）时，各个页面的 boot 逻辑（`UserListPage.jsx`、`AccountListPage.jsx` 等 15+ 处调用点）各自独立地重新调用 `persistDashboardGroupOnlyMode(true)`，如果 sessionStorage 里还残留着上一次选中的 `dashboard_selected_company_id`（没人主动清掉），`groupOnly` 就会被计算成 `false`。
- 一旦 `groupOnly` 变成 `false`，`canShowReportInSidebar` 就会转而用这个"过期"的 company id 去查公司行，因为该公司既不是 Games 也不是 C168，于是直接判定隐藏 Report。

## 修复

在唯一的写入入口 [`persistDashboardGroupOnlyMode`](../../Count-frontend/src/utils/company/sharedCompanyFilter.js:452) 里保证互斥关系，进入 Group-only 模式时强制清掉 `dashboard_selected_company_id`：

```js
export function persistDashboardGroupOnlyMode(groupOnly) {
  if (groupOnly) {
    sessionStorage.setItem(DASHBOARD_GROUP_ONLY_KEY, "1");
    sessionStorage.removeItem(DASHBOARD_SELECTED_COMPANY_KEY);
  } else {
    sessionStorage.removeItem(DASHBOARD_GROUP_ONLY_KEY);
  }
}
```

这样修一个源头函数，就能覆盖代码里所有调用 `persistDashboardGroupOnlyMode(true)` 的 20+ 处地方（Admin、Account、Data Capture、各 Maintenance 页、Report 页、Transaction 等），不需要逐个页面单独打补丁去清 company id。

## 影响范围 / 回归测试

- 修改文件：[`sharedCompanyFilter.js`](../../Count-frontend/src/utils/company/sharedCompanyFilter.js)（仅 `persistDashboardGroupOnlyMode` 一处）。
- 跑过的既有测试均通过：
  - `node --test src/utils/company/sharedCompanyFilter.partnerPill.test.js`
  - `node --test src/utils/company/domainPageForbiddenRace.test.js`
- 建议人工验证：Owner / Admin 分别登录，Group 模式下依次跳转 Admin → Account → Data Capture → Maintenance → Report，确认 Report 入口全程保持显示，不再消失。

## 备注（另一个已确认但未改代码的问题）

同一次排查中还确认了 Admin 用户列表页 "Company:" 栏为空是**数据问题**而非代码 bug：Group "OK" 名下的 OK1、OK2 两家公司的 `tenant.parent_id` 未指向 OK（未通过 Ownership → Company 页签设置 Parent Tenant），因此不会出现在该 Group 的 Company 挂靠列表里。这与 Report 消失是两个独立问题，未在本次改动中处理。
