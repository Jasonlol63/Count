package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION: undoes HALF of
 * [fix_migrated_rate_leg_account_direction_swap.sql](fix_migrated_rate_leg_account_direction_swap.sql)
 * — that script swapped {@code account_id}/{@code from_account_id} on all 364 legacy-migrated RATE
 * leg1+leg2 rows (182 {@code rate_group_id LIKE 'RATE_%'} groups) uniformly. Confirmed (against a real
 * example, tenant 95 KZ/XE, group RATE_1787825923_1982) that the swap was only correct for leg2 —
 * leg1's PRE-swap (legacy-verbatim) columns already produced the right Payment History direction and
 * sign with the app's plain/unconditional formula; swapping leg1 too broke it.
 *
 * <p>Scope of this pass: only the leg1 rows belonging to the "identical pair" subset — the 124 (of
 * 182) groups where the legacy {@code data_capture}... no, {@code transactions} row for leg1 has the
 * exact same {@code (account_id, from_account_id)} as its leg2 sibling in the legacy DB. This is the
 * subset directly verified against a real example; the remaining 58 groups (legacy leg1/leg2 accounts
 * genuinely differ, including 20 of the 23 middleman groups) are intentionally NOT touched by this
 * tool — they need individual spot-checks against the live legacy site before any fix, per
 * MIGRATION_LOG.md §34 (see write-up).
 *
 * <p>Fix: for each in-scope leg1 row, restore {@code account_id}/{@code from_account_id} to the exact
 * values still on record in the legacy staging DB (not a formulaic re-derivation) — leg2 is left
 * untouched. No code changes needed: the app's existing unconditional Cr/Dr formula and
 * {@code TransactionHistoryServiceImpl#applyRateHistoryPresentation} already handle correctly-restored
 * leg1 rows without any RATE_%-specific branch (that branch was correctly removed already, see
 * MIGRATION_LOG.md §34).
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateLeg1DirectionRevertTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--legacy-db=c168_net_legacy_20260827] [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class RateLeg1DirectionRevertTool {

    private RateLeg1DirectionRevertTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String legacyDb = "c168_net_legacy_20260827";
        String reportPath = "rate_leg1_direction_revert_report.txt";
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

            // Scope query: leg1 rows of "identical pair" groups, where the CURRENT count_real leg1
            // row still holds the post-swap (wrong) values -- i.e. matches legacy leg1's from/account
            // swapped, not its original values -- so this is safe to run even if partially applied
            // before (idempotent: WHERE clause excludes rows already reverted).
            String scopeSql =
                    "SELECT tr.rate_group_id, tr.leg1_transaction_id AS txn_id, "
                            + "       l1.account_id AS correct_account_id, l1.from_account_id AS correct_from_account_id, "
                            + "       t.account_id AS current_account_id, t.from_account_id AS current_from_account_id "
                            + "FROM transactions_rate tr "
                            + "JOIN transactions t ON t.id = tr.leg1_transaction_id "
                            + "JOIN " + legacyDb + ".transactions l1 ON l1.id = tr.leg1_transaction_id "
                            + "JOIN " + legacyDb + ".transactions l2 ON l2.id = tr.leg2_transaction_id "
                            + "WHERE tr.rate_group_id LIKE 'RATE\\_%' "
                            + "  AND l1.account_id = l2.account_id AND l1.from_account_id = l2.from_account_id "
                            + "  AND NOT (t.account_id = l1.account_id AND t.from_account_id = l1.from_account_id)";

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
