# Maintenance 侧边栏导航（Spring SPA）

Maintenance 子菜单显示规则、Bank 公司入口、以及 **Spring `tenant_has_*` 与旧 PHP `company_has_*` 字段** 约定。修改 `AuthenticatedLayout`、sidebar 权限或 Maintenance 页面守卫时，**同步更新本文档**。

相关：

- Payment Maintenance 列表/软删：[`payment-maintenance-list-delete.md`](./payment-maintenance-list-delete.md)
- Session / 登录：`login-to-business-pages.md`、`frontend-springboot-migration.md` §3.3

---

## 1. Maintenance 子菜单路由

| 菜单文案（EN） | 路由 | pageKey |
|----------------|------|---------|
| Data Capture | `/capture-maintenance` | `capture-maintenance` |
| Transaction | `/transaction-maintenance` | `transaction-maintenance` |
| Payment | `/payment-maintenance` | `payment-maintenance` |
| Formula | `/formula-maintenance` | `formula-maintenance` |
| Bank Process | `/bankprocess-maintenance` | `bankprocess-maintenance` |

实现：`Count-frontend/src/components/AuthenticatedLayout.jsx`（Maintenance flyout submenu）。

---

## 2. 谁能看到 Maintenance 父菜单

| 函数 | 含义 |
|------|------|
| `showMaintenanceInSidebar(me)` | Owner / 全权限 / 有 `maintenance` 权限 / **limited maintenance** |
| `canAccessFullMaintenance(me)` | Owner、空 permissions、或含 `maintenance` |
| `canAccessLimitedMaintenance(me)` | 非 Owner、无 `maintenance` 权限，但当前 tenant 有 Game 或 Bank |

Limited 用户仍可见 **Transaction + Formula**（及 Bank 场景下的 Capture），但 **不含 Payment / Bank Process**（需 full maintenance）。

---

## 3. 各子入口显示条件

逻辑在 `AuthenticatedLayout.jsx`；下表为 2026-07-24 行为摘要。

| 子入口 | 显示条件 |
|--------|----------|
| **Data Capture** | `(fullMaintenance \|\| (limitedMaintenance && tenant_has_bank))` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Transaction** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Payment** | `fullMaintenance` **且** `(tenant_has_game \|\| tenant_has_bank)` |
| **Formula** | `(tenant_has_game \|\| tenant_has_bank)` **且** `(fullMaintenance \|\| limitedMaintenance)` |
| **Bank Process** | `fullMaintenance` **且** `shouldShowBankprocessMaintenanceInSidebar(me)` |

### 3.1 Bank Process 专项：`shouldShowBankprocessMaintenanceInSidebar`

文件：`Count-frontend/src/utils/company/sharedCompanyFilter.js`

Bank Process Maintenance **必须绑定具体公司**（不能 Group-only 汇总视图）。

| 场景 | 是否显示 |
|------|----------|
| Dashboard **Group Only**（只选 Group、未选 Company pill） | **否** |
| **Group All**（组内「全部公司」模式） | 组内任一公司有 **Bank** permission → **是** |
| 已选具体 Company（如 BK） | 当前 session **`tenant_has_bank === true`** → **是** |

```javascript
// 正确：Spring session 字段（含 legacy fallback）
import { sessionHasTenantBank } from "../auth/sessionTenant.js";
return sessionHasTenantBank(me);

// 错误：旧 PHP 字段，Spring current-user 不返回，恒为 undefined
return Boolean(me?.company_has_bank); // ❌ 勿用
```

### 3.2 典型故障（已修复 2026-07-24）

**现象**：Owner 登录 Bank 公司（如 BK），Maintenance 有 Payment / Formula，但 **没有 Bank Process**。

**原因**：Bank Process 侧边栏误读 `company_has_bank`；Payment 等项已用 `tenant_has_bank`。

**修复**：

- `shouldShowBankprocessMaintenanceInSidebar` → `sessionHasTenantBank(me)`
- `BankprocessMaintenancePage.jsx` 进入守卫 → `sessionHasTenantBank(user)`
- `useMaintenanceBankOnlyGuard.js` → `sessionHasTenantGame` / `sessionHasTenantBank`

---

## 4. Session 字段（Spring）

来源：`SessionUser` / `GET auth/current-user` / `switch-tenant` 响应。

| 字段 | 含义 |
|------|------|
| `tenant_id` | 当前活跃租户 numeric id（= 公司 pill id） |
| `tenant_code` | 公司 code（如 `BK`、`C168`） |
| `tenant_has_game` | 租户 permissions 含 Games / Gambling |
| `tenant_has_bank` | 租户 permissions 含 **Bank** |
| `permissions[]` | 小写功能键（`maintenance`、`process` 等） |

前端统一读取：`Count-frontend/src/utils/auth/sessionTenant.js`

```javascript
sessionHasTenantBank(me)  // me.tenant_has_bank ?? me.company_has_bank
sessionHasTenantGame(me)  // me.tenant_has_game ?? me.company_has_gambling
```

切公司 pill 时，`patchMeFromCompanyContext`（`loginScope.js`）会乐观更新 `tenant_has_*`；最终以 `current-user` / `switch-tenant` 为准。

### 4.1 勿再依赖的 PHP 字段

| 旧字段 | Spring 替代 |
|--------|-------------|
| `company_has_bank` | `tenant_has_bank` / `sessionHasTenantBank(me)` |
| `company_has_gambling` | `tenant_has_game` / `sessionHasTenantGame(me)` |
| `company_id`（session 活跃租户） | `tenant_id` |

---

## 5. Bank-only 公司路由守卫

文件：`Count-frontend/src/utils/company/sidebarCompanySwitch.js`

**Bank-only** = `hasBank && !hasGambling`（如纯 Bank 公司 CX / BK）。

允许的 Maintenance 路径：

- `capture-maintenance`
- `transaction-maintenance`
- `payment-maintenance`
- `formula-maintenance`
- `bankprocess-maintenance`

其他 Maintenance 路由或 Games 专属页：切到 bank-only 公司时可能 redirect 到 `dashboard`（见 `resolveMaintenanceRedirectForSession`）。

`useMaintenanceBankOnlyGuard`：Formula 等页在 bank-only 公司下 redirect（使用 `sessionHasTenant*` 判 category）。

---

## 6. Process 菜单 vs Bank Process Maintenance

| 概念 | 路由 | 说明 |
|------|------|------|
| **Bank Process List**（Process 权限） | `/bank-process-list` | 配置 BP、Accounting Due inbox |
| **Bank Process Maintenance** | `/bankprocess-maintenance` | 维护已入账 BP 交易行（软删等） |

Bank-only 登录时 Process 侧边栏指向 `bank-process-list`（非 `process-list`）。  
Bank Process **Maintenance** 仍在 Maintenance 子菜单下，需 **maintenance 权限 + tenant_has_bank**。

---

## 7. 关键文件索引

| 层 | 路径 |
|----|------|
| 侧边栏 UI | `Count-frontend/src/components/AuthenticatedLayout.jsx` |
| 权限 | `Count-frontend/src/utils/auth/sidebarPermissions.js` |
| Bank Process 显示 | `Count-frontend/src/utils/company/sharedCompanyFilter.js` → `shouldShowBankprocessMaintenanceInSidebar` |
| Session 读取 | `Count-frontend/src/utils/auth/sessionTenant.js` |
| 切公司 patch | `Count-frontend/src/utils/company/loginScope.js` → `patchMeFromCompanyContext` |
| Category flags | `Count-frontend/src/utils/company/companyCategoryFlags.js` |
| BP Maintenance 页守卫 | `Count-frontend/pages/maintenance/bankprocess/BankprocessMaintenancePage.jsx` |
| Bank-only redirect | `Count-frontend/src/utils/company/sidebarCompanySwitch.js` |

---

## 8. 变更检查清单

- [ ] 新增 Maintenance 子入口：是否更新 `AuthenticatedLayout` **与本文 §3**  
- [ ] 是否仍用 `tenant_has_*` / `sessionHasTenant*`，而非 `company_has_*`  
- [ ] Bank Process Maintenance 是否仍要求 **具体 Company**（非 Group-only）  
- [ ] Bank-only 公司是否仍走 `sidebarCompanySwitch` 允许路径  
- [ ] 修改 `SessionUser` 字段名时：同步 `sessionTenant.js` + 本文 §4  
- [ ] Maintenance 页是否 **不回归** Category pills（§9）

---

## 9. Category 筛选条（已移除）

2026-07-24 起，**所有 Maintenance 页面不再展示** 顶部 `Category:` pills（Games / Bank / Loan / Rate / Money）。

| 项 | 约定 |
|----|------|
| UI | 不渲染 `maintenance-permission-filter-header` |
| 仍走 PHP 的页 | Transaction / Formula / Capture 等仍在内部 **自动选择** category 传给旧 API；用户不可手动切换 |
| Spring Payment / Bank Process Maintenance | 仅用 `tenantId`，本就不依赖 Category pills |
| 公司能力 | 由 Group/Company pill + session `tenant_has_*` 决定 sidebar 入口，不再重复 Category 行 |

涉及文件：`PaymentMaintenancePage`、`TransactionMaintenancePage`、`FormulaMaintenancePage`、`BankprocessMaintenanceFilters.jsx`。
