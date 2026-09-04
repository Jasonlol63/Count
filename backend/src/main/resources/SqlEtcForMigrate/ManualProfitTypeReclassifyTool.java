package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * One-off backfill: reclassify pre-migration manual account-to-account WIN/LOSE transfer rows to
 * {@code transaction_type = 'PROFIT'}.
 *
 * <p>Background (see BANK_PROCESS_DESCRIPTION_BACKFILL_LOG.md 追加章节 "Manual Profit WIN/LOSE
 * 分类回填"): the legacy PHP system had no dedicated enum value for the "手动 PROFIT Submit" feature
 * (from_account_id + to_account_id, single row, positive amount, no Bank Process / Data Capture
 * linkage) -- it stored these rows as plain {@code WIN} (never observed as {@code LOSE} in this
 * dataset), relying on {@code from_account_id} being non-null to distinguish them from real Data
 * Capture Win/Loss postings at render time. The new schema added a real {@code PROFIT} enum value
 * for this exact feature (see {@code TransactionSubmitServiceImpl#submitProfit}), but
 * {@code migrate_data_transactions_from_legacy.sql} copies {@code transaction_type} verbatim with no
 * reclassification, so these rows landed in the new system still typed {@code WIN}/{@code LOSE} and
 * got misrouted into the Data Capture branch (ID PRODUCT falls back to "DATA CAPTURE",
 * {@code TransactionHistoryServiceImpl.java:333}) instead of the PROFIT branch (ID PRODUCT "PROFIT",
 * {@code TransactionHistoryServiceImpl.java:325-326}), and the Win/Loss sign convention also differs
 * between the two branches (Data Capture: WIN=+/LOSE=-; PROFIT: sign depends on which side of
 * account_id/from_account_id the viewed account is on), so amounts displayed with the wrong sign too.
 *
 * <p>Identification rule (verified against production data before writing this tool -- 80 rows total
 * across 5 tenants: AG 54, RS 10, 95 10, 23 3, TZX 3; all {@code WIN}, none {@code LOSE}; all have a
 * blank {@code description}; none have a matching {@code data_capture_line} row):
 * <pre>
 *   transaction_type IN ('WIN','LOSE')
 *   AND from_account_id IS NOT NULL          -- real Data Capture/Bank Process WIN/LOSE rows never set this
 *   AND bank_process_posted_id IS NULL       -- confirmed 0 Bank Process rows ever have from_account_id set
 *   AND NOT EXISTS (data_capture_line row for this transaction)  -- confirmed 0/80 have one
 * </pre>
 *
 * <p>Only {@code transaction_type} is changed. {@code description} is intentionally left untouched --
 * it is already blank on every matching row, and
 * {@code TransactionHistoryServiceImpl.applyManualTransferHistoryPresentation} /
 * {@code shouldRewriteManualTransferHistoryDescription} already regenerate "PROFIT FROM {code}" /
 * "PROFIT TO {code}" at read time for any PROFIT-typed row with a blank description, exactly like a
 * freshly-submitted PROFIT transaction -- no stored text needs to be computed by this tool.
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.ManualProfitTypeReclassifyTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--apply] [--report=manual_profit_reclassify_report.txt]
 * </pre>
 */
public final class ManualProfitTypeReclassifyTool {

    private ManualProfitTypeReclassifyTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "manual_profit_reclassify_report.txt";
        boolean apply = false;

        for (String arg : args) {
            if ("--apply".equals(arg)) {
                apply = true;
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
        int reclassified = 0;
        int skippedNonBlankDescription = 0;

        String selectSql =
                "SELECT t.id, ten.code AS tenant_code, t.transaction_type, t.amount, t.description, "
                        + "t.remark, t.account_id, t.from_account_id, t.transaction_date "
                        + "FROM transactions t "
                        + "JOIN tenant ten ON ten.id = t.tenant_id "
                        + "WHERE t.transaction_type IN ('WIN','LOSE') "
                        + "  AND t.from_account_id IS NOT NULL "
                        + "  AND t.bank_process_posted_id IS NULL "
                        + "  AND NOT EXISTS (SELECT 1 FROM data_capture_line dcl WHERE dcl.transaction_id = t.id) "
                        + "ORDER BY ten.code, t.transaction_date, t.id";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath))) {
            conn.setAutoCommit(false);

            try (PreparedStatement selectPs = conn.prepareStatement(selectSql);
                 ResultSet rs = selectPs.executeQuery();
                 PreparedStatement updatePs = conn.prepareStatement(
                         "UPDATE transactions SET transaction_type = 'PROFIT' WHERE id = ?")) {

                while (rs.next()) {
                    total++;
                    int txnId = rs.getInt("id");
                    String tenantCode = rs.getString("tenant_code");
                    String oldType = rs.getString("transaction_type");
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String description = rs.getString("description");
                    String remark = rs.getString("remark");
                    int accountId = rs.getInt("account_id");
                    int fromAccountId = rs.getInt("from_account_id");

                    boolean blankDescription = description == null || description.isBlank();
                    if (!blankDescription) {
                        // Extra safety net: this dataset never hit this branch (all 80 had a blank
                        // description), but if a future re-run finds one with real text, skip it
                        // rather than silently reclassify a row whose description might not match the
                        // PROFIT-blank-description read-time regeneration assumption above.
                        skippedNonBlankDescription++;
                        report.write("SKIP txn=" + txnId + " tenant=" + tenantCode
                                + " reason=non_blank_description description=[" + description + "]");
                        report.newLine();
                        continue;
                    }

                    reclassified++;
                    report.write("RECLASSIFY txn=" + txnId + " tenant=" + tenantCode
                            + " " + oldType + " -> PROFIT"
                            + " amount=" + amount + " account=" + accountId + " from_account=" + fromAccountId
                            + " remark=[" + remark + "]");
                    report.newLine();

                    if (apply) {
                        updatePs.setInt(1, txnId);
                        updatePs.addBatch();
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
                "total=%d reclassified=%d skipped_non_blank_description=%d mode=%s report=%s",
                total, reclassified, skippedNonBlankDescription, apply ? "APPLY" : "PREVIEW", reportPath);
        System.out.println(summary);
    }
}
