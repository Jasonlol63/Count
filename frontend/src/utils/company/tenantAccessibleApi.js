/** Accessible tenants — Spring Boot `GET /auth/tenant-accessible` (replaces get_owner_companies_api.php). */

import { buildApiUrl } from "../core/apiUrl.js";

function normalizeTenantCode(value) {
  if (value == null || String(value).trim() === "") return null;
  return String(value).trim().toUpperCase();
}

function inferTenantType(tenantCode, parentTenantCode) {
  const code = String(tenantCode || "").trim().toUpperCase();
  const parent = String(parentTenantCode || "").trim().toUpperCase();
  if (!code && parent) return "GROUP";
  if (code && parent && code === parent) return "GROUP";
  return "COMPANY";
}

/** Map Spring tenant-accessible row JSON → internal tenant model (no company_id / group_id). */
export function normalizeTenantAccessibleItem(row) {
  if (!row || typeof row !== "object") return null;

  const tenantId = Number(row.tenantId ?? row.id);
  if (!Number.isFinite(tenantId) || tenantId <= 0) return null;

  const tenantCode = String(
    row.tenantCode ?? row.code ?? row.company_id ?? row.companyId ?? "",
  ).trim();
  const parentTenantCode = normalizeTenantCode(
    row.parentTenantCode ?? row.parentGroupCode ?? row.group_id ?? row.groupId,
  );
  const nativeParentTenantCode = normalizeTenantCode(
    row.nativeParentTenantCode ?? row.native_group_id ?? row.nativeGroupId ?? parentTenantCode,
  );
  const rawType = row.tenantType != null ? String(row.tenantType).trim().toUpperCase() : "";
  const tenantType =
    rawType === "GROUP" || rawType === "COMPANY"
      ? rawType
      : inferTenantType(tenantCode, parentTenantCode);

  return {
    tenantId,
    tenantCode,
    parentTenantCode,
    nativeParentTenantCode,
    expirationDate: row.expirationDate ?? row.expiration_date ?? null,
    tenantType,
  };
}

/** Map tenant model → UI company picker row (company_id / group_id unchanged for display). */
export function tenantAccessibleRowToUiCompany(tenant) {
  if (!tenant) return null;
  const code = tenant.tenantCode;
  const isGroup = tenant.tenantType === "GROUP";
  const parent = tenant.parentTenantCode;
  const native = tenant.nativeParentTenantCode;

  return {
    id: tenant.tenantId,
    company_id: code,
    group_id: isGroup ? code.toUpperCase() : parent,
    native_group_id: isGroup ? (native || code).toUpperCase() : native ?? parent,
    expiration_date: tenant.expirationDate,
  };
}

export function readAccessibleParentTenantCodes(json) {
  if (Array.isArray(json?.accessibleParentTenantCodes)) {
    return json.accessibleParentTenantCodes.map(normalizeTenantCode).filter(Boolean);
  }
  if (Array.isArray(json?.accessible_group_ids)) {
    return json.accessible_group_ids.map(normalizeTenantCode).filter(Boolean);
  }
  return [];
}

/**
 * GET /auth/tenant-accessible
 * @returns {Promise<{ tenants: object[], accessibleParentTenantCodes: string[], raw: object }>}
 */
export async function fetchAccessibleTenants(options = {}) {
  const { signal, all = true, throwOnError = false } = options;
  const res = await fetch(buildApiUrl(`auth/tenant-accessible?all=${all ? 1 : 0}`), {
    credentials: "include",
    signal,
  });
  const json = await res.json();

  if (throwOnError && (!res.ok || !json?.success || !Array.isArray(json?.data))) {
    throw new Error(json?.message || json?.error || "Failed to load accessible tenants");
  }

  const tenants = Array.isArray(json?.data)
    ? json.data.map(normalizeTenantAccessibleItem).filter(Boolean)
    : [];
  const accessibleParentTenantCodes = readAccessibleParentTenantCodes(json);

  return { tenants, accessibleParentTenantCodes, raw: json };
}
