/** Tenant currency master — Spring Boot `/api/currency/*`. */

import { buildApiUrl } from "../core/apiUrl.js";
import { applyTenantLedgerToParams, LEDGER_GROUP } from "../company/tenantLedgerParams.js";
import { resolveListTenantId } from "../../pages/userlist/userListApi.js";

export const resolveCurrencyTenantId = resolveListTenantId;

/** Resolve tenant.id from ledger scope + page context (company pill id === tenant.id). */
export function resolveCurrencyTenantIdFromScope({
  ledgerScope = null,
  companyId = null,
  anchorCompanyId = null,
  selectedCompanyIds = [],
} = {}) {
  const groupOnly = ledgerScope?.ledger === LEDGER_GROUP;
  const scopeCompanyId =
    ledgerScope?.ledger === LEDGER_GROUP ? null : ledgerScope?.companyId ?? companyId;

  const fromSelected = (selectedCompanyIds || [])
    .map((id) => Number(id))
    .find((id) => Number.isFinite(id) && id > 0);

  return resolveCurrencyTenantId({
    companyId: scopeCompanyId ?? fromSelected ?? companyId,
    groupOnly,
    anchorCompanyId,
    scopeCompanyId: companyId,
  });
}

function normalizeSyncSource(raw) {
  const value = String(raw?.syncSource ?? raw?.sync_source ?? "MANUAL").trim().toUpperCase();
  return value === "SUBSIDIARY" ? "subsidiary" : "manual";
}

/** Map Spring currency JSON → UI row shape used across account modals. */
export function normalizeCurrencyRow(raw, { isLinked = false } = {}) {
  const syncSource = normalizeSyncSource(raw);
  return {
    id: Number(raw?.id),
    code: String(raw?.code ?? ""),
    is_linked: !!isLinked,
    sync_source: syncSource,
    deletable: syncSource !== "subsidiary",
  };
}

async function parseJsonResponse(res) {
  const json = await res.json();
  return { res, json };
}

/**
 * POST /api/currency/list?tenant_id=
 * @returns {Promise<object[]>} raw Spring currency rows
 */
export async function fetchCurrencyListByTenantId(tenantId, signal) {
  const tid = Number(tenantId);
  if (!Number.isFinite(tid) || tid <= 0) {
    throw new Error("tenantIdRequired");
  }

  const { res, json } = await parseJsonResponse(
    await fetch(buildApiUrl(`api/currency/list?tenant_id=${encodeURIComponent(tid)}`), {
      method: "POST",
      credentials: "include",
      signal,
    }),
  );

  if (!res.ok || !json.success) {
    throw new Error(json?.message || "failedToLoadCurrencies");
  }

  return Array.isArray(json.data) ? json.data : [];
}

/** Legacy PHP helper — linked flags when editing an existing account. */
async function fetchLinkedCurrencyIdSet(accountId, ledgerScope, signal) {
  const aid = Number(accountId);
  if (!Number.isFinite(aid) || aid <= 0) return new Set();

  const params = new URLSearchParams({
    action: "get_available_currencies",
    account_id: String(aid),
  });
  if (ledgerScope) applyTenantLedgerToParams(params, ledgerScope);

  const { res, json } = await parseJsonResponse(
    await fetch(buildApiUrl(`api/accounts/account_currency_api.php?${params.toString()}`), {
      credentials: "include",
      signal,
    }),
  );

  if (!res.ok || !json.success || !Array.isArray(json.data)) return new Set();
  return new Set(json.data.filter((c) => c.is_linked).map((c) => Number(c.id)));
}

/**
 * Tenant currencies for account modals (Spring list + optional PHP is_linked merge).
 * @returns {Promise<object[]>}
 */
export async function fetchAvailableCurrencies(
  {
    tenantId = null,
    ledgerScope = null,
    companyId = null,
    anchorCompanyId = null,
    selectedCompanyIds = [],
    accountId = null,
  } = {},
  signal,
) {
  const tid =
    tenantId != null
      ? Number(tenantId)
      : resolveCurrencyTenantIdFromScope({
          ledgerScope,
          companyId,
          anchorCompanyId,
          selectedCompanyIds,
        });

  if (!Number.isFinite(tid) || tid <= 0) {
    throw new Error("tenantIdRequired");
  }

  const [rows, linkedIds] = await Promise.all([
    fetchCurrencyListByTenantId(tid, signal),
    fetchLinkedCurrencyIdSet(accountId, ledgerScope, signal),
  ]);

  return rows
    .map((row) => normalizeCurrencyRow(row, { isLinked: linkedIds.has(Number(row.id)) }))
    .filter((row) => Number.isFinite(row.id) && row.id > 0);
}

/**
 * POST /api/currency/add
 * @returns {Promise<{ id: number, code: string }>}
 */
export async function createCurrency(
  {
    code,
    tenantId = null,
    ledgerScope = null,
    companyId = null,
    anchorCompanyId = null,
    selectedCompanyIds = [],
  },
  signal,
) {
  const normalizedCode = String(code || "")
    .trim()
    .toUpperCase();
  if (!normalizedCode) {
    throw new Error("currencyCodeRequired");
  }

  const tid =
    tenantId != null
      ? Number(tenantId)
      : resolveCurrencyTenantIdFromScope({
          ledgerScope,
          companyId,
          anchorCompanyId,
          selectedCompanyIds,
        });

  if (!Number.isFinite(tid) || tid <= 0) {
    throw new Error("tenantIdRequired");
  }

  const { res, json } = await parseJsonResponse(
    await fetch(buildApiUrl("api/currency/add"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ tenantId: String(tid), code: normalizedCode }),
      signal,
    }),
  );

  if (!res.ok || !json.success || !json.data) {
    const err = new Error(json?.message || "createFailed");
    err.response = json;
    throw err;
  }

  return {
    id: Number(json.data.id),
    code: String(json.data.code ?? normalizedCode),
  };
}

/**
 * POST /api/currency/delete?id=&tenantId=
 * When `force` is true, falls back to legacy PHP (usage checks / cascade).
 */
export async function deleteCurrency(
  {
    id,
    tenantId = null,
    ledgerScope = null,
    companyId = null,
    anchorCompanyId = null,
    force = false,
  },
  signal,
) {
  const currencyId = Number(id);
  if (!Number.isFinite(currencyId) || currencyId <= 0) {
    throw new Error("invalidRequest");
  }

  const tid =
    tenantId != null
      ? Number(tenantId)
      : resolveCurrencyTenantIdFromScope({ ledgerScope, companyId });

  if (!Number.isFinite(tid) || tid <= 0) {
    throw new Error("tenantIdRequired");
  }

  if (force) {
    const deleteUrl = new URL(buildApiUrl("api/accounts/delete_currency_api.php"));
    if (ledgerScope) applyTenantLedgerToParams(deleteUrl.searchParams, ledgerScope);
    const payload = { id: currencyId, force: true };
    if (ledgerScope?.ledger === LEDGER_GROUP) {
      payload.group_only = true;
      if (ledgerScope.groupId) payload.group_id = ledgerScope.groupId;
    } else {
      if (ledgerScope?.companyId) payload.company_id = ledgerScope.companyId;
      if (ledgerScope?.groupId) payload.group_id = ledgerScope.groupId;
    }

    const { res, json } = await parseJsonResponse(
      await fetch(deleteUrl.toString(), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify(payload),
        signal,
      }),
    );

    return {
      success: Boolean(json.success),
      message: String(json.message || json.error || ""),
      data: json.data ?? null,
      status: res.status,
    };
  }

  const { res, json } = await parseJsonResponse(
    await fetch(
      buildApiUrl(
        `api/currency/delete?id=${encodeURIComponent(currencyId)}&tenantId=${encodeURIComponent(tid)}`,
      ),
      {
        method: "POST",
        credentials: "include",
        signal,
      },
    ),
  );

  return {
    success: Boolean(json.success),
    message: String(json.message || json.error || ""),
    data: json.data ?? null,
    status: res.status,
  };
}
