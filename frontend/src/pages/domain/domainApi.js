import { buildApiUrl } from "../../utils/core/apiUrl.js";

async function postJson(path, body) {
  const res = await fetch(buildApiUrl(path), {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: body != null ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => ({}));
  return { res, json };
}

/** Map Spring Boot DomainListItemDto → legacy Domain page row shape. */
export function mapDomainListItemFromApi(item) {
  const groups = Array.isArray(item?.groups) ? item.groups : [];
  const companies = Array.isArray(item?.companies) ? item.companies : [];
  const groupCodes = groups
    .map((g) => String(g?.code ?? "").trim().toUpperCase())
    .filter(Boolean)
    .sort();
  const companiesFull = companies
    .map((c) => {
      const companyId = String(c?.code ?? "").trim().toUpperCase();
      if (!companyId) return null;
      const parent = c?.parent_code ?? c?.parentCode ?? null;
      return {
        company_id: companyId,
        expiration_date: c?.expiration_date ?? c?.expirationDate ?? null,
        group_id: parent ? String(parent).trim().toUpperCase() : null,
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.company_id.localeCompare(b.company_id));

  return {
    id: item?.id,
    owner_code: item?.owner_code ?? item?.ownerCode,
    name: item?.name,
    email: item?.email,
    created_by: item?.created_by ?? item?.createdBy,
    created_at: item?.created_at ?? item?.createdAt,
    group_ids: groupCodes.length ? groupCodes.join(", ") : null,
    companies_full: companiesFull,
    companies: companiesFull.map((c) => c.company_id).join(", "),
    _api_groups: groups,
    _api_companies: companies,
  };
}

export async function fetchDomainList() {
  const { res, json } = await postJson("api/domain/list");
  if (!res.ok || !json?.success) {
    throw new Error(json?.message || "Failed to load domains");
  }
  const rows = Array.isArray(json?.data?.domains) ? json.data.domains : [];
  return rows.map(mapDomainListItemFromApi);
}

export async function validateDomainCode(code, excludeOwnerId) {
  const { json } = await postJson("api/domain/validate-code", {
    code: String(code ?? "").trim(),
    exclude_owner_id: excludeOwnerId ?? null,
  });
  return json;
}

function toGroupSaveDto(entry) {
  return {
    code: String(entry?.group_code ?? entry?.code ?? "").trim().toUpperCase(),
    expiration_date: entry?.expiration_date ?? null,
  };
}

function toCompanySaveDto(entry) {
  const parent = entry?.group_id ?? entry?.parent_code ?? entry?.parentCode ?? null;
  return {
    code: String(entry?.company_id ?? entry?.code ?? "").trim().toUpperCase(),
    expiration_date: entry?.expiration_date ?? null,
    parent_code: parent ? String(parent).trim().toUpperCase() : null,
  };
}

export async function createDomain({
  ownerCode,
  name,
  email,
  password,
  secondaryPassword,
  groups,
  companies,
}) {
  const { json } = await postJson("api/domain/add", {
    owner_code: ownerCode,
    name,
    email,
    password,
    secondary_password: secondaryPassword,
    groups: (groups || []).map(toGroupSaveDto),
    companies: (companies || []).map(toCompanySaveDto),
  });
  return json;
}
