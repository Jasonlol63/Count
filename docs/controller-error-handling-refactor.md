# Controller 错误处理统一 — 删除重复 try/catch + error() helper

> **范围**：`GlobalExceptionHandler` 改成统一响应格式；19 个 Controller（+ `AuthController` 部分方法）删掉各自的
> `try/catch (BusinessException e) { return error(e); }` 和私有 `error()` helper；`AuditLogController` 对齐同一格式。
> **最后更新**：2026-09-15

---

## 1. 起因

`PlatformSettingController` 里有这么一段：

```java
private static ResponseEntity<Map<String, Object>> error(BusinessException e) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", false);
    body.put("message", e.getMessage());
    body.put("data", null);
    return ResponseEntity.ok(body);
}
```

查了一下发现这段代码在 19 个 Controller 里几乎一字不差地重复了一遍（每个接口一个 `try { ... } catch (BusinessException e) { return error(e); }`）。项目里本来就有 `GlobalExceptionHandler`（`@RestControllerAdvice`）在全局捕获 `BusinessException`，但它当时的响应格式是 `{"status":"error","message":...}`——跟 19 个 Controller 手写的 `{"success":false,"message":...,"data":null}` 不是同一个格式，所以没法直接删掉这些重复代码去依赖全局处理器，删了格式会变。

## 2. 做法

1. **先统一格式**：把 `GlobalExceptionHandler` 的三个 `@ExceptionHandler`（`BusinessException`、`MissingServletRequestParameterException`、兜底 `Exception`）都改成同时带 `status` 和 `success` 两个字段：
   ```java
   body.put("status", "error");
   body.put("success", false);
   body.put("message", ...);
   body.put("data", ...);
   ```
   两个字段都留，不是「随便选一个」——见下面 §3。

2. **删除 19 个 Controller + `AuthController` 部分方法里的重复代码**：`AdminController`、`AnnouncementController`、`AuthController`（仅 `tenant-accessible`/`tenant-by-code` 两个方法，`/login` 本身没有这段）、`AutoRenewController`、`BankCountryOptionController`、`BankProcessController`、`BkProcessAccountingDueController`、`CurrencyController`、`DashboardController`、`DataCaptureController`、`DataCaptureSummaryController`、`DomainController`、`MaintenanceController`、`MemberController`、`PlatformSettingController`、`ProcessController`、`ReportController`、`TenantOwnershipController`、`TransactionContraInboxController`、`TransactionController`。每个文件的改法一样：去掉 `try {`/`} catch (BusinessException e) { return error(e); }`（或内联版本），删掉私有 `error()` helper，异常直接往外抛，交给 `GlobalExceptionHandler`。

3. **`AuditLogController` 对齐**：它是这一批里最新写的，当时图省事直接抄了 `GlobalExceptionHandler` 原本的 `status`-only 格式，跟其余 Controller 常年用的 `success`-based 格式不是同一套——属于少数派写法，一并改成 `success/message/data`，前端 `AuditLogPage.jsx` 同步把 `json.status === "success"` 改成 `json.success === true`。

## 3. 查出来的一个坑：`status` 字段不是随便能删的

原本以为 `GlobalExceptionHandler` 的 `status` 字段和各 Controller 手写的 `success` 字段是两套冗余但等价的东西，删哪个都行。实际去查 `Count-frontend` 才发现不是这么回事：

- `Count-frontend/src/pages/ownership/company/useCompanyOwnership.js`、`.../group/useGroupEarnings.js` **直接**写 `res.status === "success"` 来判断 `/api/ownership/list`、`/api/ownership/available-accounts` 这两个接口成功与否，**没有 `success` 字段兜底**。
- `Count-frontend/src/pages/dashboard` 底下则完全没人读 `.status`，只认 `success`。

所以：
- `GlobalExceptionHandler` 的错误响应**两个字段都保留**（`status` + `success`），因为不确定还有没有别的页面在悄悄依赖 `status`，两个都给是最安全的选择，成本也是零。
- `TenantOwnershipController` 的**成功**响应体里，`body.put("status", "success")` 这一行**特意保留**，没有跟着其他 Controller 一起精简掉——因为 Ownership 页面的前端代码真的在用这个字段判断成功。`DashboardController` 原本也带 `status`，但查证前端后确认没人用，这个才删掉了。

**结论**：`TenantOwnershipController` 现在跟其他 Controller 在「错误处理机制」上是完全对齐的（都不再自己 catch，统一交给 `GlobalExceptionHandler`），但在「成功响应体的字段」上没有强行对齐——这是刻意保留的差异，不是遗漏。以后如果要把它也精简成只有 `success`，必须先把上面两个前端文件的 `res.status === "success"` 改成 `res.success`，前后端要一起改，不能只删后端这一行。

## 4. 改动范围（用于 commit 时单独 `git add`）

- `backend/src/main/java/com/eazycount/handler/GlobalExceptionHandler.java`
- 19 个 Controller（见 §2 列表）+ `AuthController.java`
- `AuditLogController.java`（对齐格式，非本次新增）
- 前端：`Count-frontend/src/pages/auditlog/AuditLogPage.jsx`（`json.status` → `json.success`）

## 5. 已知缺口

- `TenantOwnershipController` 的成功响应体仍然是 `status` + `success` 双字段并存的过渡状态，没有彻底统一成单一约定——见 §3。
- 没有跑一次完整的手动回归测试（尤其是 Ownership 的 `link-partner`/`batch-save-ownership` 这几个改动前后行为容易出错的接口，因为它们的 `resolveTenantId` 抛的是 `IllegalArgumentException` 不是 `BusinessException`，这次改动没有改变它的处理路径，但没有实测确认）。
