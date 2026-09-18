package com.eazycount.audit;

import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Announcements;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.Currency;
import com.eazycount.entity.DataCaptureFormula;
import com.eazycount.entity.Maintenance;
import com.eazycount.entity.Owner;
import com.eazycount.entity.PlatformSetting;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Transaction;
import com.eazycount.entity.User;
import com.eazycount.entity.UserLink;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditSnapshots {

    private AuditSnapshots() {
    }

    /** Deliberately excludes password/secondaryPassword — never belongs in an audit trail. */
    public static Map<String, Object> owner(Owner o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("owner_code", o.getOwnerCode());
        s.put("name", o.getName());
        s.put("email", o.getEmail());
        s.put("status", o.getStatus());
        return s;
    }

    /**
     * Deliberately excludes password/secondaryPassword — never belongs in an audit trail.
     *
     * <p>{@code tenantIds}/{@code permissionCodes}: neither {@code user_tenant_access} (company
     * binding) nor {@code user_permission_override} (custom sidebar permissions) is its own
     * audited write (both are plain DAO calls inline in createAdmin/updateAdmin) — folded in
     * here as a stopgap so they're at least visible somewhere, same approach as Account's
     * tenant_ids (see AuditSnapshots.user). Coarser than a dedicated audit row per table; revisit
     * if/when there's a clearer direction for cascaded writes in general.
     */
    public static Map<String, Object> admin(Admin a, java.util.List<Integer> tenantIds, java.util.List<String> permissionCodes) {
        if (a == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("login_id", a.getLoginId());
        s.put("name", a.getName());
        s.put("email", a.getEmail());
        s.put("role_id", a.getRoleId());
        s.put("role_code", a.getRoleCode());
        s.put("status", a.getStatus());
        s.put("read_only", a.getReadOnly());
        s.put("permission_mode", a.getPermissionMode());
        s.put("tenant_ids", tenantIds);
        s.put("permission_codes", permissionCodes);
        return s;
    }

    public static Map<String, Object> tenantSetting(Tenant t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("code", t.getCode());
        s.put("name", t.getName());
        s.put("expiration_date", t.getExpirationDate());
        return s;
    }

    /**
     * Deliberately excludes password — never belongs in an audit trail.
     *
     * <p>{@code tenantIds}: {@code account_tenant_access} isn't its own audited write (no
     * separate Service call for it, just a DAO call inline in createUser/updateUser) — folded
     * in here as a stopgap so the company-binding change is at least visible somewhere, rather
     * than fully unaudited. Coarser than a dedicated audit row (no per-company before/after,
     * just the resulting id list) — revisit if/when there's a clearer direction for cascaded
     * writes in general.
     */
    public static Map<String, Object> user(User u, java.util.List<Integer> tenantIds) {
        if (u == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("account_id", u.getAccountId());
        s.put("name", u.getName());
        s.put("role", u.getRole());
        s.put("status", u.getStatus());
        s.put("payment_alert", u.getPaymentAlert());
        s.put("alert_day", u.getAlertDay());
        s.put("alert_amount", u.getAlertAmount());
        s.put("alert_specific_date", u.getAlertSpecificDate());
        s.put("remark", u.getRemark());
        s.put("tenant_ids", tenantIds);
        return s;
    }

    /** Deliberately excludes password — never belongs in an audit trail. See {@link #user(User, java.util.List)}. */
    public static Map<String, Object> user(UserListDTO u, java.util.List<Integer> tenantIds) {
        if (u == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("account_id", u.getAccountId());
        s.put("name", u.getName());
        s.put("role", u.getRole());
        s.put("status", u.getStatus());
        s.put("remark", u.getRemark());
        s.put("tenant_ids", tenantIds);
        return s;
    }

    public static Map<String, Object> userLink(UserLink l) {
        if (l == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("account_id_1", l.getAccountId1());
        s.put("account_id_2", l.getAccountId2());
        s.put("tenant_id", l.getTenantId());
        s.put("link_type", l.getLinkType());
        s.put("source_account_id", l.getSourceAccountId());
        return s;
    }

    public static Map<String, Object> currency(Currency c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("code", c.getCode());
        s.put("sync_source", c.getSyncSource());
        s.put("status", c.getStatus());
        return s;
    }

    public static Map<String, Object> autoRenewStatus(AutoRenewDTO r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("status", r.getStatus());
        s.put("period", r.getPeriod());
        s.put("price", r.getPrice());
        s.put("new_expiration_date", r.getNewExpirationDate());
        s.put("processed_by", r.getProcessedBy());
        return s;
    }

    public static Map<String, Object> announcement(Announcements a) {
        if (a == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("title", a.getTitle());
        s.put("content", a.getContent());
        s.put("company_code", a.getCompanyCode());
        s.put("status", a.getStatus());
        s.put("user_type", a.getUserType());
        return s;
    }

    public static Map<String, Object> maintenance(Maintenance m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("prefix", m.getPrefix());
        s.put("content", m.getContent());
        s.put("company_code", m.getCompanyCode());
        s.put("status", m.getStatus());
        s.put("user_type", m.getUserType());
        return s;
    }

    public static Map<String, Object> transaction(Transaction t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", t.getId());
        snapshot.put("tenant_id", t.getTenantId());
        snapshot.put("transaction_type", t.getTransactionType());
        snapshot.put("account_id", t.getAccountId());
        snapshot.put("from_account_id", t.getFromAccountId());
        snapshot.put("currency_id", t.getCurrencyId());
        snapshot.put("amount", t.getAmount());
        snapshot.put("transaction_date", t.getTransactionDate());
        snapshot.put("description", t.getDescription());
        snapshot.put("remark", t.getRemark());
        snapshot.put("created_by", t.getCreatedBy());
        snapshot.put("updated_by", t.getUpdatedBy());
        snapshot.put("approval_status", t.getApprovalStatus());
        snapshot.put("approved_by", t.getApprovedBy());
        snapshot.put("approved_at", t.getApprovedAt());
        snapshot.put("bank_process_posted_id", t.getBankProcessPostedId());
        snapshot.put("bank_process_id", t.getBankProcessId());
        snapshot.put("rate_group_id", t.getRateGroupId());
        snapshot.put("created_at", t.getCreatedAt());
        snapshot.put("updated_at", t.getUpdatedAt());
        return snapshot;
    }

    public static Map<String, Object> formula(DataCaptureFormula f) {
        if (f == null) {
            return null;
        }
        Map<String, Object> s = new HashMap<>();
        s.put("id_product", f.getIdProduct());
        s.put("description", f.getDescription());
        s.put("source_columns", f.getSourceColumns());
        s.put("formula", f.getFormula());
        s.put("input_method", f.getInputMethod());
        s.put("source_percent", f.getSourcePercent());
        s.put("account_id", f.getAccountId());
        s.put("currency_id", f.getCurrencyId());
        return s;
    }

    /**
     * Fuller formula snapshot than {@link #formula} — includes ids and audit metadata
     * (created_by/at, updated_by/at), not just the user-editable fields. Used where the
     * snapshot needs to support manual row restoration (Formula Maintenance's delete),
     * as opposed to {@link #formula}'s lighter "what changed" snapshot for a plain edit.
     */
    public static Map<String, Object> formulaFull(DataCaptureFormula f) {
        if (f == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", f.getId());
        snapshot.put("formula_group_id", f.getFormulaGroupId());
        snapshot.put("tenant_id", f.getTenantId());
        snapshot.put("process_id", f.getProcessId());
        snapshot.put("id_product", f.getIdProduct());
        snapshot.put("product_type", f.getProductType());
        snapshot.put("parent_id_product", f.getParentIdProduct());
        snapshot.put("account_id", f.getAccountId());
        snapshot.put("currency_id", f.getCurrencyId());
        snapshot.put("description", f.getDescription());
        snapshot.put("formula", f.getFormula());
        snapshot.put("input_method", f.getInputMethod());
        snapshot.put("source_percent", f.getSourcePercent());
        snapshot.put("enable_source_percent", f.getEnableSourcePercent());
        snapshot.put("enable_input_method", f.getEnableInputMethod());
        snapshot.put("created_by", f.getCreatedBy());
        snapshot.put("updated_by", f.getUpdatedBy());
        snapshot.put("created_at", f.getCreatedAt());
        snapshot.put("updated_at", f.getUpdatedAt());
        return snapshot;
    }

    /** {@code bank_country} column names, not {@link BankCountry}'s Java field names. */
    public static Map<String, Object> bankCountry(BankCountry c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", c.getId());
        snapshot.put("tenant_id", c.getTenantId());
        snapshot.put("code", c.getCode());
        snapshot.put("created_at", c.getCreatedAt());
        return snapshot;
    }

    /** {@code bank_option} column names, not {@link BankOption}'s Java field names. */
    public static Map<String, Object> bankOption(BankOption o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", o.getId());
        snapshot.put("tenant_id", o.getTenantId());
        snapshot.put("country_id", o.getCountryId());
        snapshot.put("name", o.getName());
        snapshot.put("created_at", o.getCreatedAt());
        return snapshot;
    }

    /** {@code bank_process} column names, not {@link BankProcess}'s Java field names. */
    public static Map<String, Object> bankProcess(BankProcess bp) {
        if (bp == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", bp.getId());
        snapshot.put("tenant_id", bp.getTenantId());
        snapshot.put("country_id", bp.getCountryId());
        snapshot.put("bank_option_id", bp.getBankOptionId());
        snapshot.put("card_owner", bp.getCardOwner());
        snapshot.put("card_owner_type", bp.getCardOwnerType());
        snapshot.put("day_start", bp.getDayStart());
        snapshot.put("day_end", bp.getDayEnd());
        snapshot.put("day_end_monthly_cap_enabled", bp.getDayEndMonthlyCapEnabled());
        snapshot.put("expired_at_creation", bp.getExpiredAtCreation());
        snapshot.put("due_generation_floor", bp.getDueGenerationFloor());
        snapshot.put("frequency", bp.getFrequency());
        snapshot.put("supplier_account_id", bp.getSupplierAccountId());
        snapshot.put("supplier_price", bp.getSupplierPrice());
        snapshot.put("customer_account_id", bp.getCustomerAccountId());
        snapshot.put("customer_price", bp.getCustomerPrice());
        snapshot.put("company_account_id", bp.getCompanyAccountId());
        snapshot.put("company_price", bp.getCompanyPrice());
        snapshot.put("contract", bp.getContract());
        snapshot.put("insurance_price", bp.getInsurancePrice());
        snapshot.put("sop", bp.getSop());
        snapshot.put("remark", bp.getRemark());
        snapshot.put("status", bp.getStatus());
        snapshot.put("resend_schedule_day_start", bp.getResendScheduleDayStart());
        snapshot.put("resend_schedule_day_end", bp.getResendScheduleDayEnd());
        snapshot.put("resend_schedule_frequency", bp.getResendScheduleFrequency());
        snapshot.put("created_by", bp.getCreatedBy());
        snapshot.put("updated_by", bp.getUpdatedBy());
        snapshot.put("created_at", bp.getCreatedAt());
        snapshot.put("updated_at", bp.getUpdatedAt());
        return snapshot;
    }

    /**
     * {@code transactions} column names for a Bank Balance Contra — a narrower field set than
     * {@link #transaction}, scoped to what's needed when a Bank Balance row is removed.
     */
    public static Map<String, Object> bankBalance(Transaction t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", t.getId());
        snapshot.put("tenant_id", t.getTenantId());
        snapshot.put("transaction_type", t.getTransactionType());
        snapshot.put("account_id", t.getAccountId());
        snapshot.put("from_account_id", t.getFromAccountId());
        snapshot.put("currency_id", t.getCurrencyId());
        snapshot.put("amount", t.getAmount());
        snapshot.put("transaction_date", t.getTransactionDate());
        snapshot.put("bank_process_id", t.getBankProcessId());
        snapshot.put("created_by", t.getCreatedBy());
        snapshot.put("created_at", t.getCreatedAt());
        return snapshot;
    }

    /** {@code platform_settings} column names, not {@link PlatformSetting}'s Java field names. */
    public static Map<String, Object> platformSetting(PlatformSetting s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", s.getId());
        snapshot.put("telegram_support_link", s.getTelegramSupportLink());
        snapshot.put("updated_by", s.getUpdatedBy());
        snapshot.put("updated_by_type", s.getUpdatedByType());
        snapshot.put("updated_at", s.getUpdatedAt());
        return snapshot;
    }

    /** {@code process_description} column names, not {@link ProcessDescription}'s Java field names. */
    public static Map<String, Object> processDescription(ProcessDescription d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", d.getId());
        snapshot.put("tenant_id", d.getTenantId());
        snapshot.put("name", d.getName());
        snapshot.put("created_at", d.getCreatedAt());
        return snapshot;
    }

    /** {@code process} column names, not {@link Process}'s Java field names. */
    public static Map<String, Object> process(Process p) {
        if (p == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", p.getId());
        snapshot.put("tenant_id", p.getTenantId());
        snapshot.put("category", p.getCategory());
        snapshot.put("code", p.getCode());
        snapshot.put("copied_from_process_id", p.getCopiedFromProcessId());
        snapshot.put("enable_save_draft", p.getEnableSaveDraft());
        snapshot.put("currency_id", p.getCurrencyId());
        snapshot.put("remove_word", p.getRemoveWord());
        snapshot.put("replace_word_from", p.getReplaceWordFrom());
        snapshot.put("replace_word_to", p.getReplaceWordTo());
        snapshot.put("remark", p.getRemark());
        snapshot.put("status", p.getStatus());
        snapshot.put("created_by", p.getCreatedBy());
        snapshot.put("updated_by", p.getUpdatedBy());
        snapshot.put("created_at", p.getCreatedAt());
        snapshot.put("updated_at", p.getUpdatedAt());
        return snapshot;
    }
}
