/** User list — Spring Boot `/api/userlist/list` (tenant-scoped admin rows). */

import { buildApiUrl } from "../../utils/core/apiUrl.js";

/**
 * Map UI company scope (company_id pill) → Spring tenant_id for list API.
 * company.id in the picker === tenant.id in the backend.
 */
export function resolveListTenantId({
  companyId = null,
  groupOnly = false,
  anchorCompanyId = null,
  scopeCompanyId = null,
} = {}) {
  const direct = companyId != null ? Number(companyId) : Number.NaN;
  if (Number.isFinite(direct) && direct > 0) return direct;

  if (groupOnly) {
    const anchor = anchorCompanyId != null ? Number(anchorCompanyId) : Number.NaN;
    if (Number.isFinite(anchor) && anchor > 0) return anchor;
  }

  const scope = scopeCompanyId != null ? Number(scopeCompanyId) : Number.NaN;
  if (Number.isFinite(scope) && scope > 0) return scope;

  return null;
}

/** Map Spring {@link AdminListDTO} JSON to camelCase list row. */
export function normalizeAdminListItem(item) {
  const admin = item?.admin ?? {};
  const access = item?.adminTenantAccess ?? {};
  const role = admin.role != null ? String(admin.role) : "";
  const status = admin.status != null ? String(admin.status) : "";

  return {
    id: admin.id,
    loginId: admin.loginId ?? "",
    name: admin.name ?? "",
    email: admin.email ?? "",
    role,
    permissions: admin.permissions ?? null,
    status,
    createdBy: admin.createdBy ?? "",
    createdAt: admin.createdAt ?? null,
    lastLogin: admin.lastLogin ?? null,
    readOnly: admin.readOnly ?? false,
    isOwnerShadow: false,
    tenantAccess: {
      id: access.id ?? null,
      userId: access.userId ?? null,
      tenantId: access.tenantId ?? null,
      capabilities: access.capabilities ?? null,
      accountPermissions: access.accountPermissions ?? null,
      processPermissions: access.processPermissions ?? null,
      createdAt: access.createdAt ?? null,
      updatedAt: access.updatedAt ?? null,
    },
  };
}

/** Owner shadow row from legacy PHP detail — normalized to the same list shape. */
export function normalizeOwnerShadowRow(detail) {
  if (!detail) return null;
  return {
    id: detail.id,
    loginId: detail.login_id ?? detail.loginId ?? "",
    name: detail.name ?? "",
    email: detail.email ?? "",
    role: detail.role ?? "owner",
    permissions: detail.permissions ?? null,
    status: detail.status ?? "",
    createdBy: detail.created_by ?? detail.createdBy ?? "",
    createdAt: detail.created_at ?? detail.createdAt ?? null,
    lastLogin: detail.last_login ?? detail.lastLogin ?? null,
    readOnly: false,
    isOwnerShadow: true,
    tenantAccess: null,
  };
}

/**
 * POST /api/userlist/list?tenant_id=
 * @returns {Promise<object[]>}
 */
export async function fetchAdminListByTenantId(tenantId, signal) {
  const tid = Number(tenantId);
  if (!Number.isFinite(tid) || tid <= 0) {
    throw new Error("tenantIdRequired");
  }

  const res = await fetch(buildApiUrl(`api/userlist/list?tenant_id=${encodeURIComponent(tid)}`), {
    method: "POST",
    credentials: "include",
    signal,
  });
  const json = await res.json();
  if (!res.ok || !json.success) {
    throw new Error(json?.message || "failedToLoadUsers");
  }

  return Array.isArray(json.data) ? json.data.map(normalizeAdminListItem) : [];
}

/** Resolve tenant.id list for Spring create — company.id in picker === tenant.id. */
export function resolveAdminCreateTenantIds({
  useDualTenantUserPicker = false,
  selectedGroupIds = [],
  selectedCompanyIds = [],
  saveCompanyIds = [],
  shouldForceGroupScope = false,
  currentUserRole = "",
  companyId = null,
  mutationScopeCompanyId = null,
} = {}) {
  if (useDualTenantUserPicker) {
    const ids = [...selectedGroupIds, ...selectedCompanyIds]
      .map((id) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0);
    return [...new Set(ids)];
  }

  if (shouldForceGroupScope) {
    const source = saveCompanyIds.length ? saveCompanyIds : selectedCompanyIds;
    const ids = source.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0);
    if (ids.length) return [...new Set(ids)];
  }

  const fromPicker = saveCompanyIds.length ? saveCompanyIds : selectedCompanyIds;
  const pickerIds = fromPicker.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0);
  if (pickerIds.length) return [...new Set(pickerIds)];

  if (currentUserRole !== "admin" && currentUserRole !== "owner") {
    const scope =
      mutationScopeCompanyId != null ? Number(mutationScopeCompanyId) : Number(companyId);
    if (Number.isFinite(scope) && scope > 0) return [scope];
  }

  const fallback = companyId != null ? Number(companyId) : Number.NaN;
  return Number.isFinite(fallback) && fallback > 0 ? [fallback] : [];
}

/** Build Spring {@link AdminRequest} body for POST /api/userlist/add. */
export function buildAdminCreateRequest({
  loginId,
  name,
  email,
  password,
  secondaryPassword,
  role,
  status,
  readOnly,
  permissions,
  tenantIds,
  accountPermissions,
  processPermissions,
}) {
  return {
    loginId,
    name,
    email,
    password,
    secondaryPassword: secondaryPassword || undefined,
    role,
    status: status || "active",
    readOnly: readOnly != null ? !!readOnly : true,
    permissions,
    tenantIds,
    accountPermissions,
    processPermissions,
  };
}

/**
 * POST /api/userlist/add
 * @returns {Promise<object>} normalized list row
 */
export async function createAdminUser(request, signal) {
  const res = await fetch(buildApiUrl("api/userlist/add"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(request),
    signal,
  });
  const json = await res.json();
  if (!res.ok || !json.success) {
    throw new Error(json?.message || "saveFailed");
  }
  return normalizeAdminListItem(json.data);
}
