package com.eazycount.service.impl;

import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BkProcessAccountingPosted;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * One-off backfill: regenerate {@code transactions.description} for pre-migration Bank Process
 * WIN/LOSE lines using the exact same formatting rules the live posting flow uses for new records
 * ({@link BankAccountingDueServiceImpl#buildLineDescription}), instead of the raw legacy text those
 * rows carried over from the old PHP system's migration (see MIGRATION_LOG.md §12/§13).
 *
 * <p>Scope: only transactions with {@code bank_process_posted_id IS NOT NULL} (i.e. already correctly
 * linked to a {@code bank_process_accounting_posted} ledger row). The ~15 unlinked "orphan" rows
 * (MIGRATION_LOG.md §13.2/§13.4) are intentionally out of scope for this pass.
 *
 * <p>This class is a standalone JDBC tool (no Spring context needed) so it can be run in isolation
 * against a target MySQL instance without touching the running application. It re-uses the exact
 * static description-formatting methods of {@link BankAccountingDueServiceImpl} (widened from
 * {@code private} to package-private for this purpose, no logic changed) so the backfilled text is
 * guaranteed identical in format to what the live system would generate today.
 *
 * <p>Usage (default is preview-only — no writes):
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.BankProcessDescriptionBackfillTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--tenant=82] [--apply] [--report=backfill_report.txt]
 * </pre>
 */
public final class BankProcessDescriptionBackfillTool {

    private BankProcessDescriptionBackfillTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "backfill_report.txt";
        boolean apply = false;
        Integer tenantFilter = null;

        for (String arg : args) {
            if ("--apply".equals(arg)) {
                apply = true;
            } else if (arg.startsWith("--tenant=")) {
                tenantFilter = Integer.parseInt(arg.substring("--tenant=".length()));
            } else if (arg.startsWith("--url=")) {
                url = arg.substring("--url=".length());
            } else if (arg.startsWith("--user=")) {
                user = arg.substring("--user=".length());
            } else if (arg.startsWith("--password=")) {
                password = arg.substring("--password=".length());
            } else if (arg.startsWith("--report=")) {
                reportPath = arg.substring("--report=".length());
            }
        }

        Class.forName("com.mysql.cj.jdbc.Driver");

        int total = 0;
        int changed = 0;
        int unchanged = 0;
        int skipped = 0;

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath))) {
            conn.setAutoCommit(false);

            Map<Integer, String> bankNameByOptionId = loadBankOptionNames(conn);
            Map<String, BigDecimal> shareAmountCache = new HashMap<>();

            String sql = "SELECT t.id, t.tenant_id, t.account_id, t.amount, t.description AS old_description, "
                    + "bpap.period_type, bpap.posted_date, bpap.billing_start, bpap.billing_end, "
                    + "bp.id AS bp_id, bp.contract, bp.status, bp.frequency, bp.resend_schedule_frequency, bp.bank_option_id, "
                    + "bp.supplier_account_id, bp.supplier_price, bp.customer_account_id, bp.customer_price, "
                    + "bp.company_account_id, bp.company_price "
                    + "FROM transactions t "
                    + "JOIN bank_process_accounting_posted bpap ON bpap.id = t.bank_process_posted_id "
                    + "JOIN bank_process bp ON bp.id = bpap.bank_process_id "
                    + "WHERE t.bank_process_posted_id IS NOT NULL "
                    + (tenantFilter != null ? "AND t.tenant_id = " + tenantFilter + " " : "")
                    + "ORDER BY t.tenant_id, bp.id, t.transaction_date, t.id";

            try (PreparedStatement listPs = conn.prepareStatement(sql);
                 ResultSet rs = listPs.executeQuery();
                 PreparedStatement updatePs = conn.prepareStatement(
                         "UPDATE transactions SET description = ? WHERE id = ?")) {

                while (rs.next()) {
                    total++;
                    int txnId = rs.getInt("id");
                    int tenantId = rs.getInt("tenant_id");
                    int accountId = rs.getInt("account_id");
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String oldDescription = rs.getString("old_description");

                    BkProcessAccountingPosted.PeriodType periodType =
                            BkProcessAccountingPosted.PeriodType.valueOf(rs.getString("period_type"));
                    LocalDate postedDate = toLocalDate(rs.getDate("posted_date"));
                    LocalDate billingStart = toLocalDate(rs.getDate("billing_start"));
                    LocalDate billingEnd = toLocalDate(rs.getDate("billing_end"));

                    BankProcess bp = new BankProcess();
                    int bpId = rs.getInt("bp_id");
                    bp.setId(bpId);
                    bp.setContract(rs.getString("contract"));
                    bp.setStatus(enumOrNull(BankProcess.Status.class, rs.getString("status")));
                    bp.setFrequency(enumOrNull(BankProcess.Frequency.class, rs.getString("frequency")));
                    bp.setResendScheduleFrequency(
                            enumOrNull(BankProcess.Frequency.class, rs.getString("resend_schedule_frequency")));

                    Integer bankOptionId = getNullableInt(rs, "bank_option_id");
                    String bankName = bankOptionId != null ? bankNameByOptionId.getOrDefault(bankOptionId, "") : "";

                    Integer supplierAccountId = getNullableInt(rs, "supplier_account_id");
                    Integer customerAccountId = getNullableInt(rs, "customer_account_id");
                    Integer companyAccountId = getNullableInt(rs, "company_account_id");

                    boolean compensation = BankAccountingDueServiceImpl.isCompensationPost(bp, periodType);

                    BigDecimal baseAmount = null;
                    String leg;
                    if (supplierAccountId != null && supplierAccountId == accountId) {
                        baseAmount = rs.getBigDecimal("supplier_price");
                        leg = "SUPPLIER";
                    } else if (customerAccountId != null && customerAccountId == accountId) {
                        baseAmount = rs.getBigDecimal("customer_price");
                        leg = "CUSTOMER";
                    } else if (companyAccountId != null && companyAccountId == accountId) {
                        baseAmount = rs.getBigDecimal("company_price");
                        leg = "COMPANY";
                    } else {
                        String cacheKey = bpId + ":" + accountId;
                        baseAmount = shareAmountCache.computeIfAbsent(cacheKey,
                                k -> resolveShareAmount(conn, bpId, accountId));
                        leg = "SHARE";
                    }

                    if (!compensation && baseAmount == null) {
                        skipped++;
                        report.write("SKIP txn=" + txnId + " tenant=" + tenantId + " bankProcess=" + bpId
                                + " account=" + accountId
                                + " reason=no_matching_leg_or_share old_description=[" + oldDescription + "]");
                        report.newLine();
                        continue;
                    }

                    String newDescription;
                    try {
                        newDescription = BankAccountingDueServiceImpl.buildLineDescription(
                                bp, periodType, postedDate, billingStart, billingEnd,
                                nz(amount), nz(baseAmount), bankName, compensation);
                    } catch (RuntimeException ex) {
                        skipped++;
                        report.write("SKIP txn=" + txnId + " tenant=" + tenantId + " bankProcess=" + bpId
                                + " leg=" + leg + " reason=" + ex.getClass().getSimpleName() + ":" + ex.getMessage()
                                + " old_description=[" + oldDescription + "]");
                        report.newLine();
                        continue;
                    }

                    boolean same = newDescription != null && newDescription.equals(oldDescription);
                    if (same) {
                        unchanged++;
                    } else {
                        changed++;
                        report.write("CHANGE txn=" + txnId + " tenant=" + tenantId + " bankProcess=" + bpId
                                + " leg=" + leg + " periodType=" + periodType
                                + "\n  old=[" + oldDescription + "]"
                                + "\n  new=[" + newDescription + "]");
                        report.newLine();

                        if (apply) {
                            updatePs.setString(1, newDescription);
                            updatePs.setInt(2, txnId);
                            updatePs.addBatch();
                        }
                    }
                }

                if (apply) {
                    updatePs.executeBatch();
                }
            }

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }
        }

        String summary = String.format(
                "total=%d changed=%d unchanged=%d skipped=%d mode=%s report=%s",
                total, changed, unchanged, skipped, apply ? "APPLY" : "PREVIEW", reportPath);
        System.out.println(summary);
    }

    private static BigDecimal resolveShareAmount(Connection conn, int bankProcessId, int accountId) {
        String sql = "SELECT amount FROM bank_process_share WHERE bank_process_id = ? AND account_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bankProcessId);
            ps.setInt(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<Integer, String> loadBankOptionNames(Connection conn) throws SQLException {
        Map<Integer, String> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM bank_option");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getInt("id"), rs.getString("name"));
            }
        }
        return result;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toLocalDate(Date date) {
        return date != null ? date.toLocalDate() : null;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value != null && !value.isBlank() ? Enum.valueOf(type, value) : null;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
