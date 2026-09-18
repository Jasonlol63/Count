package com.eazycount.audit;

/**
 * Shared phrasing helpers for hand-written {@link AuditContext#captureSummary} calls — the
 * counterpart to {@link AuditSummaryDefaults} for call sites that need a name/identity only the
 * writing method already has in hand (e.g. an account's display name, a contract's card owner),
 * so they can't go through the generic before/after-snapshot fallback. Pure string formatting
 * only — no DAO access here, callers resolve their own values and pass them in.
 */
public final class AuditLabels {

    private AuditLabels() {
    }

    /** "创建新{label} {identifier}" — identifier omitted when blank. */
    public static String create(String label, String identifier) {
        return "创建新" + label + suffix(identifier);
    }

    /** "更新{label} {identifier} 状态" — identifier omitted when blank. */
    public static String updateStatus(String label, String identifier) {
        return "更新" + label + suffix(identifier) + " 状态";
    }

    /** "更新{label} {identifier}" + (" 的 " + diff, only when diff is present). */
    public static String updateWithDiff(String label, String identifier, String diff) {
        String base = "更新" + label + suffix(identifier);
        return diff == null || diff.isBlank() ? base : base + " 的 " + diff;
    }

    /** "GAME- {code}" / "BANK- {code}" — the category-prefix format shared by Data Capture and its Maintenance page. */
    public static String categoryProcess(boolean isGame, String code) {
        return (isGame ? "GAME" : "BANK") + "- " + code;
    }

    /** "创建新交易 {type} · {fromName} → {toName} · {amountText}" — two-account transfers. */
    public static String transferTransaction(String type, String fromName, String toName, String amountText) {
        return "创建新交易 " + type + " · " + fromName + " → " + toName + " · " + amountText;
    }

    /** "创建新交易 {type} · {accountName} · {amountText}" — single-account transactions (e.g. ADJUSTMENT). */
    public static String accountTransaction(String type, String accountName, String amountText) {
        return "创建新交易 " + type + " · " + accountName + " · " + amountText;
    }

    private static String suffix(String identifier) {
        return identifier == null || identifier.isBlank() ? "" : " " + identifier;
    }
}
