package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION: phase 2 of the leg1 revert started by
 * [RateLeg1DirectionRevertTool.java](RateLeg1DirectionRevertTool.java) (see MIGRATION_LOG.md §34).
 *
 * <p>Phase 1 only covered the 124 "identical pair" groups (legacy leg1 and leg2 share the exact same
 * {@code (account_id, from_account_id)}). A user-reported example outside that subset — tenant 95,
 * XE / API-DS / KZ, group {@code RATE_1786958381_4813}, a genuine 3-party CNY-&gt;MYR conversion where
 * leg1's account differs from leg2's account — was checked by hand and showed the exact same failure
 * mode: legacy leg1 (unswapped) already produces the correct Payment History direction/sign under the
 * app's plain formula, matching count168.com; only leg2 needed the physical column swap. So the "leg1
 * should never have been swapped" finding generalizes beyond the "identical pair" subset.
 *
 * <p>Scope of phase 2: all remaining leg1 rows (of the 182 {@code RATE_%} groups) that are NOT already
 * reverted AND do not carry a from_account_id collision risk — i.e.
 * {@code legacy_leg1.from_account_id <> current_leg2.from_account_id}. A collision here would mean
 * leg1 (after revert) and leg2 (already swapped) end up sharing the same {@code from_account_id},
 * which risks confusing
 * {@link TransactionHistoryServiceImpl#mergeRateMiddlemanDeductionsIntoMainLeg} (picks the RATE
 * "main line" a group's middleman fee rows merge into by matching {@code fromAccountId} against the
 * viewed account — two candidate rows with the same {@code fromAccountId} is ambiguous). Confirmed by
 * read-only query: 28 of the remaining 58 groups have this collision (18 of those also have a
 * middleman configured); the other 30 are collision-free and covered by this tool. The 28 collision
 * groups are intentionally left for a separate, dedicated pass — see MIGRATION_LOG.md §34 for the
 * running count.
 *
 * <p>Same fix mechanics as phase 1: restore leg1's {@code account_id}/{@code from_account_id} to the
 * exact values on record in the legacy staging DB; leg2 and all code are untouched.
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateLeg1DirectionRevertPhase2Tool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--legacy-db=c168_net_legacy_20260827] [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class RateLeg1DirectionRevertPhase2Tool {

    private RateLeg1DirectionRevertPhase2Tool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String legacyDb = "c168_net_legacy_20260827";
        String reportPath = "rate_leg1_direction_revert_phase2_report.txt";
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
            } else if (arg.startsWith("--legacy-db=")) {
                legacyDb = arg.substring("--legacy-db=".length());
            } else if (arg.startsWith("--report=")) {
                reportPath = arg.substring("--report=".length());
            }
        }

        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath));
             Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);

            String scopeSql =
                    "SELECT tr.rate_group_id, tr.leg1_transaction_id AS txn_id, "
                            + "       l1.account_id AS correct_account_id, l1.from_account_id AS correct_from_account_id, "
                            + "       t1.account_id AS current_account_id, t1.from_account_id AS current_from_account_id "
                            + "FROM transactions_rate tr "
                            + "JOIN transactions t1 ON t1.id = tr.leg1_transaction_id "
                            + "JOIN transactions t2 ON t2.id = tr.leg2_transaction_id "
                            + "JOIN " + legacyDb + ".transactions l1 ON l1.id = tr.leg1_transaction_id "
                            + "WHERE tr.rate_group_id LIKE 'RATE\\_%' "
                            + "  AND NOT (t1.account_id = l1.account_id AND t1.from_account_id = l1.from_account_id) "
                            + "  AND l1.from_account_id <> t2.from_account_id";

            int total = 0;
            int updated = 0;
            try (ResultSet rs = st.executeQuery(scopeSql)) {
                while (rs.next()) {
                    total++;
                    int txnId = rs.getInt("txn_id");
                    int correctAccountId = rs.getInt("correct_account_id");
                    int correctFromAccountId = rs.getInt("correct_from_account_id");
                    int currentAccountId = rs.getInt("current_account_id");
                    int currentFromAccountId = rs.getInt("current_from_account_id");

                    report.write(String.format(
                            "REVERT group=%s leg1_txn=%d  account_id %d->%d  from_account_id %d->%d",
                            rs.getString("rate_group_id"), txnId,
                            currentAccountId, correctAccountId, currentFromAccountId, correctFromAccountId));
                    report.newLine();

                    if (apply) {
                        try (Statement updSt = conn.createStatement()) {
                            updated += updSt.executeUpdate(String.format(
                                    "UPDATE transactions SET account_id = %d, from_account_id = %d WHERE id = %d",
                                    correctAccountId, correctFromAccountId, txnId));
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
                    "in_scope_leg1_rows=%d updated=%d mode=%s report=%s",
                    total, updated, apply ? "APPLY" : "PREVIEW", reportPath);
            System.out.println(summary);
            report.write(summary);
            report.newLine();
        }
    }
}
