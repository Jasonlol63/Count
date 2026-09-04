package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION: backfills {@code rate_group_id} on legacy-migrated single-sided
 * "Rate charge (xN) from CCY amount" fee rows (see MIGRATION_LOG.md §34 follow-up discussion).
 *
 * <p>Background: the legacy PHP RATE feature recorded a Middle-Man markup fee as TWO independent
 * single-sided {@code transactions} rows sharing the same date/amount/description (one on the
 * "RATE" profit-collection account, one on the real counterparty account) -- functionally the exact
 * same thing today's app calls a Rate-Mul/Service Fee middleman leg (see
 * {@code TransactionSubmitServiceImpl#submitRate}, which always writes those with the group's
 * {@code rate_group_id}). The legacy rows were migrated verbatim with {@code rate_group_id = NULL}
 * because the old schema never had that column to carry over -- not because the migration script
 * dropped it. That NULL causes them to be silently excluded from both the domain Cr/Dr aggregate and
 * the dedicated rate-middleman Win/Loss aggregate (both require an exact {@code rate_group_id} match),
 * undercounting the account's balance on the Search/List summary page (though the Payment History
 * per-row listing, which has no such filter, already shows them correctly).
 *
 * <p>Matching verified read-only before writing this tool: parsing the referenced currency+amount out
 * of the "Rate charge (xN) from CCY amount" description text and joining back to
 * {@code transactions_rate} by (tenant, currency_from code, amount_from, leg1's exact transaction_date)
 * resolves all 47 in-scope rows to exactly one candidate group each -- zero ambiguous, zero unmatched.
 * A separate 3-row group of orphan RATE rows with a different description format
 * ("Transaction to X (Rate: Y)") is NOT touched by this tool -- explicitly out of scope per user
 * request, tracked separately.
 *
 * <p>Only {@code rate_group_id} is written; no amounts, descriptions, or other columns are touched, and
 * no mapper/code changes are needed -- once these rows carry the correct {@code rate_group_id}, the
 * existing {@code rateMiddlemanFeeLeg}/{@code rateTransferLegOnly} SQL fragments (unchanged, already
 * reverted back to their original form) recognize them as genuine middleman fee legs of a real group,
 * exactly like freshly-submitted MARKUP rows.
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateChargeOrphanBackfillTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class RateChargeOrphanBackfillTool {

    private RateChargeOrphanBackfillTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "rate_charge_orphan_backfill_report.txt";
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

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath));
             Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);

            String orphanSql =
                    "SELECT t.id, t.tenant_id, t.transaction_date, "
                            + "       TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(t.description, 'from ', -1), ' ', 1)) AS parsed_ccy, "
                            + "       CAST(TRIM(SUBSTRING_INDEX(t.description, ' ', -1)) AS DECIMAL(20,2)) AS parsed_amount "
                            + "FROM transactions t "
                            + "WHERE t.transaction_type = 'RATE' AND t.rate_group_id IS NULL "
                            + "  AND t.description LIKE 'Rate charge%' "
                            + "ORDER BY t.tenant_id, t.transaction_date, t.id";

            String candidateSql =
                    "SELECT tr.rate_group_id "
                            + "FROM transactions_rate tr "
                            + "JOIN currency cf ON cf.id = tr.currency_from_id "
                            + "JOIN transactions l1 ON l1.id = tr.leg1_transaction_id "
                            + "WHERE tr.tenant_id = ? AND cf.code = ? "
                            + "  AND ABS(tr.amount_from - ?) < 0.02 "
                            + "  AND l1.transaction_date = ?";

            int total = 0;
            int matched = 0;
            int skippedAmbiguous = 0;
            int updated = 0;

            try (PreparedStatement orphanPs = conn.prepareStatement(orphanSql);
                 ResultSet rs = orphanPs.executeQuery();
                 PreparedStatement candPs = conn.prepareStatement(candidateSql)) {

                while (rs.next()) {
                    total++;
                    int txnId = rs.getInt("id");
                    int tenantId = rs.getInt("tenant_id");
                    String parsedCcy = rs.getString("parsed_ccy");
                    BigDecimal parsedAmount = rs.getBigDecimal("parsed_amount");
                    java.sql.Date txnDate = rs.getDate("transaction_date");

                    candPs.setInt(1, tenantId);
                    candPs.setString(2, parsedCcy);
                    candPs.setBigDecimal(3, parsedAmount);
                    candPs.setDate(4, txnDate);

                    String matchedGroupId = null;
                    int candidateCount = 0;
                    try (ResultSet crs = candPs.executeQuery()) {
                        while (crs.next()) {
                            candidateCount++;
                            matchedGroupId = crs.getString("rate_group_id");
                        }
                    }

                    if (candidateCount != 1) {
                        skippedAmbiguous++;
                        report.write(String.format(
                                "SKIP txn=%d tenant=%d date=%s ccy=%s amount=%s candidates=%d",
                                txnId, tenantId, txnDate, parsedCcy, parsedAmount, candidateCount));
                        report.newLine();
                        continue;
                    }

                    matched++;
                    report.write(String.format(
                            "BACKFILL txn=%d tenant=%d date=%s ccy=%s amount=%s -> rate_group_id=%s",
                            txnId, tenantId, txnDate, parsedCcy, parsedAmount, matchedGroupId));
                    report.newLine();

                    if (apply) {
                        try (PreparedStatement updPs = conn.prepareStatement(
                                "UPDATE transactions SET rate_group_id = ? WHERE id = ?")) {
                            updPs.setString(1, matchedGroupId);
                            updPs.setInt(2, txnId);
                            updated += updPs.executeUpdate();
                        }
                    }
                }
            }

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }

            String summary = String.format(
                    "total=%d matched=%d skipped_ambiguous_or_unmatched=%d updated=%d mode=%s report=%s",
                    total, matched, skippedAmbiguous, updated, apply ? "APPLY" : "PREVIEW", reportPath);
            System.out.println(summary);
            report.write(summary);
            report.newLine();
        }
    }
}
