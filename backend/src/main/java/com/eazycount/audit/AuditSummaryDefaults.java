package com.eazycount.audit;

import com.eazycount.entity.AuditLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generic fallback for {@code audit_log.summary} when the writing method didn't stage one via
 * {@link AuditContext#captureSummary} — see docs/it-role-audit-log.md. Covers the modules that
 * don't need a hand-written sentence (a handful of others go through captureSummary directly,
 * e.g. anything needing an account-name/country-name lookup this class deliberately has no DB
 * access to do).
 *
 * <p>Keyed by {@code module + "|" + sourceTable}, not sourceTable alone — {@code
 * data_capture_formula} is written by both DATA_CAPTURE (light "what changed" wording) and
 * FORMULA_MAINTENANCE (name-only diff), and {@code transactions} is written by several
 * Maintenance-page deletes wanting the same "{transaction_type} 交易" label.
 */
public final class AuditSummaryDefaults {

    private AuditSummaryDefaults() {
    }

    private record EntityConfig(String label, List<String> identifierKeys, String labelTemplate, boolean nameOnlyDiff,
                                boolean noDiff, String roleLabelField, String identifierConnector) {
    }

    /** Audit-trail/structural columns every snapshot carries — never the "what changed" a human cares about. */
    private static final Set<String> EXCLUDED_DIFF_KEYS = Set.of(
            "id", "tenant_id", "created_at", "updated_at", "created_by", "updated_by");

    /**
     * {@code user.role_code} → the Chinese role name shown on the Admin page's own role picker
     * (see userListLogic.js's ALL_ROLE_OPTIONS) — used so an Admin audit row reads "更新经理 MS"
     * rather than a generic "更新管理员 MS" that doesn't say what this user actually is.
     */
    private static final Map<String, String> ROLE_LABEL = Map.of(
            "OWNER", "Owner",
            "PARTNERSHIP", "合伙",
            "ADMIN", "管理员",
            "MANAGER", "经理",
            "SUPERVISOR", "主管",
            "ACCOUNTANT", "会计",
            "AUDIT", "审计",
            "CUSTOMER_SERVICE", "客服");

    private static final Map<String, EntityConfig> CONFIG = new LinkedHashMap<>();

    private static void put(String module, String sourceTable, String label, List<String> identifierKeys) {
        CONFIG.put(module + "|" + sourceTable, new EntityConfig(label, identifierKeys, null, false, false, null, null));
    }

    private static void putNameOnlyDiff(String module, String sourceTable, String label, List<String> identifierKeys) {
        CONFIG.put(module + "|" + sourceTable, new EntityConfig(label, identifierKeys, null, true, false, null, null));
    }

    /**
     * Like {@link #putNoDiff}, but the identifier is joined with a custom connector word instead
     * of a plain space — e.g. "用户关联" + "给 " + "GPOK7" reads as "用户关联给 GPOK7". The connector
     * is only appended when an identifier is actually found, so a row with no identifier (e.g. a
     * delete path that never captured one) still reads cleanly without a dangling connector.
     */
    private static void putNoDiffWithConnector(String module, String sourceTable, String label,
                                               List<String> identifierKeys, String identifierConnector) {
        CONFIG.put(module + "|" + sourceTable,
                new EntityConfig(label, identifierKeys, null, false, true, null, identifierConnector));
    }

    /**
     * UPDATE/CREATE/DELETE all say "which one" via {@code roleLabelField} instead of a fixed
     * label — the entity's own {@code role_code} (or similar) decides the noun, e.g. "经理"/"客服"
     * rather than a generic "管理员" that doesn't say what this user actually is. Field diff is
     * still suppressed (a role change already shows up as its own field, no need to also see it
     * duplicated in the label).
     */
    private static void putRoleLabel(String module, String sourceTable, String fallbackLabel,
                                     List<String> identifierKeys, String roleLabelField) {
        CONFIG.put(module + "|" + sourceTable,
                new EntityConfig(fallbackLabel, identifierKeys, null, false, true, roleLabelField, null));
    }

    /** UPDATE just says "which one", no field diff — for rows where the specific field changed isn't the point. */
    private static void putNoDiff(String module, String sourceTable, String label, List<String> identifierKeys) {
        CONFIG.put(module + "|" + sourceTable, new EntityConfig(label, identifierKeys, null, false, true, null, null));
    }

    private static void putLabelTemplate(String module, String sourceTable, String labelTemplate) {
        CONFIG.put(module + "|" + sourceTable, new EntityConfig(null, List.of(), labelTemplate, false, false, null, null));
    }

    static {
        put("ANNOUNCEMENT", "announcements", "公告", List.of());
        put("ANNOUNCEMENT", "maintenance_marquee", "公告", List.of());
        put("BANK_COUNTRY", "bank_country", "银行国家", List.of("code"));
        put("BANK_OPTION", "bank_option", "收款银行", List.of("name"));
        put("AUTO_RENEW", "tenant_auto_renew", "自动续费申请", List.of());
        put("ADMIN", "owner", "Owner", List.of("owner_code"));
        put("DOMAIN", "owner", "Owner", List.of("owner_code"));
        putRoleLabel("ADMIN", "user", "管理员", List.of("login_id"), "role_code");
        put("ACCOUNT", "account", "用户", List.of("name"));
        putNoDiffWithConnector("ACCOUNT", "account_link", "用户关联", List.of("linked_account_name"), "给 ");
        put("ACCOUNT", "currency", "货币", List.of("code"));
        put("ACCOUNT", "account_currency", "用户货币绑定", List.of());
        put("DOMAIN_TENANT_SETTING", "tenant", "租户设置", List.of("code"));
        put("DOMAIN_FEE_SETTINGS", "domain_list_fee_price", "域名费用设置", List.of());
        put("SYSTEM_MAINTENANCE", "system_maintenance_mode", "系统维护模式", List.of());
        put("PLATFORM_SETTING", "platform_settings", "平台设置", List.of());
        put("PROCESS", "process", "流程", List.of("code"));
        put("PROCESS_DESCRIPTION", "process_description", "流程描述", List.of("name"));
        put("DATA_CAPTURE", "data_capture_formula", "公式", List.of("id_product"));
        put("DATA_CAPTURE", "data_captures", "数据抓取", List.of("category"));
        put("CAPTURE_MAINTENANCE", "data_capture_line", "数据抓取", List.of("category"));
        putNameOnlyDiff("FORMULA_MAINTENANCE", "data_capture_formula", "公式", List.of("id_product"));
        // BANK_PROCESS create/update write their own richer captureSummary (合同 + country/bank
        // names) — this entry is only the fallback for status/remark/delete paths that don't.
        put("BANK_PROCESS", "bank_process", "合同", List.of("card_owner"));
        // TRANSACTION always writes its own captureSummary — this entry is a safety net only,
        // in case a future submit branch forgets to.
        put("TRANSACTION", "transactions", "交易", List.of("transaction_type"));
        putLabelTemplate("PAYMENT_MAINTENANCE", "transactions", "{transaction_type} 交易");
        putLabelTemplate("BANK_PROCESS_MAINTENANCE", "transactions", "{transaction_type} 交易");
    }

    /** Core sentence only — no trailing company name; {@code AuditLogServiceImpl.record()} appends that centrally. */
    @SuppressWarnings("unchecked")
    public static String build(String module, String sourceTable, AuditLog.Action action, Object before, Object after) {
        EntityConfig config = CONFIG.get(module + "|" + sourceTable);
        if (config == null) {
            return null;
        }
        Map<String, Object> beforeMap = before instanceof Map ? (Map<String, Object>) before : Map.of();
        Map<String, Object> afterMap = after instanceof Map ? (Map<String, Object>) after : Map.of();

        String label = config.labelTemplate() != null
                ? resolveLabelTemplate(config.labelTemplate(), afterMap, beforeMap)
                : resolveRoleLabel(config, afterMap, beforeMap, action);
        String identifier = config.identifierKeys().isEmpty()
                ? null
                : firstNonBlank(config.identifierKeys(), action == AuditLog.Action.DELETE ? beforeMap : afterMap);

        String connector = config.identifierConnector() != null ? config.identifierConnector() : " ";

        StringBuilder sb = new StringBuilder();
        switch (action) {
            case CREATE -> {
                sb.append("创建新").append(label);
                appendIdentifier(sb, identifier, connector);
            }
            case DELETE -> {
                sb.append("删除").append(label);
                appendIdentifier(sb, identifier, connector);
            }
            case UPDATE -> {
                sb.append("更新").append(label);
                appendIdentifier(sb, identifier, connector);
                if (!config.noDiff()) {
                    Set<String> changed = changedKeys(beforeMap, afterMap);
                    if (changed.size() == 1 && changed.contains("status")) {
                        // Status toggles don't need the raw old→new arrow — "updated" already
                        // implies a change, so just name the field, not the value swing.
                        sb.append(" 状态");
                    } else {
                        String diff = buildDiff(beforeMap, afterMap, config.nameOnlyDiff(), false);
                        if (diff != null) {
                            sb.append(" 的 ").append(diff);
                        }
                    }
                }
            }
            case RESTORE -> {
                sb.append("恢复").append(label);
                appendIdentifier(sb, identifier, connector);
            }
        }
        return sb.toString();
    }

    /** For call sites that already have their own identifier text but still want the generic field diff. */
    @SuppressWarnings("unchecked")
    public static String diffFields(Object before, Object after) {
        Map<String, Object> beforeMap = before instanceof Map ? (Map<String, Object>) before : Map.of();
        Map<String, Object> afterMap = after instanceof Map ? (Map<String, Object>) after : Map.of();
        return buildDiff(beforeMap, afterMap, false, false);
    }

    /** Same as {@link #diffFields} but names only, quoted — e.g. {@code "insurance_price"}. */
    @SuppressWarnings("unchecked")
    public static String diffFieldNames(Object before, Object after) {
        Map<String, Object> beforeMap = before instanceof Map ? (Map<String, Object>) before : Map.of();
        Map<String, Object> afterMap = after instanceof Map ? (Map<String, Object>) after : Map.of();
        return buildDiff(beforeMap, afterMap, true, true);
    }

    private static void appendIdentifier(StringBuilder sb, String identifier, String connector) {
        if (identifier != null && !identifier.isBlank()) {
            sb.append(connector).append(identifier);
        }
    }

    private static String resolveRoleLabel(EntityConfig config, Map<String, Object> after, Map<String, Object> before,
                                           AuditLog.Action action) {
        if (config.roleLabelField() == null) {
            return config.label();
        }
        Map<String, Object> source = action == AuditLog.Action.DELETE ? before : after;
        Object roleCode = source.get(config.roleLabelField());
        if (roleCode == null) {
            roleCode = before.get(config.roleLabelField());
        }
        if (roleCode == null) {
            return config.label();
        }
        return ROLE_LABEL.getOrDefault(String.valueOf(roleCode).trim().toUpperCase(Locale.ROOT), config.label());
    }

    private static String resolveLabelTemplate(String template, Map<String, Object> after, Map<String, Object> before) {
        int start = template.indexOf('{');
        int end = template.indexOf('}');
        if (start < 0 || end < 0) {
            return template;
        }
        String key = template.substring(start + 1, end);
        Object value = !after.isEmpty() ? after.get(key) : null;
        if (value == null) {
            value = before.get(key);
        }
        return template.substring(0, start) + display(value) + template.substring(end + 1);
    }

    private static String firstNonBlank(List<String> keys, Map<String, Object> map) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** Field keys whose value actually differs between before/after, excluding structural columns. */
    private static Set<String> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeAll(EXCLUDED_DIFF_KEYS);
        keys.removeIf(key -> Objects.equals(before.get(key), after.get(key)));
        return keys;
    }

    private static String buildDiff(Map<String, Object> before, Map<String, Object> after,
                                    boolean nameOnly, boolean quotedNames) {
        if (before.isEmpty() && after.isEmpty()) {
            return null;
        }
        List<String> changed = new ArrayList<>();
        for (String key : changedKeys(before, after)) {
            if (nameOnly) {
                changed.add(quotedNames ? "\"" + key + "\"" : key);
            } else {
                changed.add(key + "：" + display(before.get(key)) + " → " + display(after.get(key)));
            }
        }
        if (changed.isEmpty()) {
            return null;
        }
        if (changed.size() > 2) {
            return changed.size() + " 项字段";
        }
        return String.join(nameOnly ? "、" : "，", changed);
    }

    private static String display(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }
}
