package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION: phase 3 (final) of the leg1 revert started by
 * [RateLeg1DirectionRevertTool.java](RateLeg1DirectionRevertTool.java) /
 * [RateLeg1DirectionRevertPhase2Tool.java](RateLeg1DirectionRevertPhase2Tool.java) — see
 * MIGRATION_LOG.md §34.
 *
 * <p>Phases 1+2 covered 154 of the 182 {@code RATE_%} groups (everywhere reverting leg1 would NOT make
 * its {@code from_account_id} collide with leg2's current {@code from_account_id}). The remaining 28
 * were held back over a concern that leg2 might ALSO need reverting in these more complex (often
 * 3-party, sometimes with a "RATE" middleman account) groups, unlike the simple two-party groups phases
 * 1-2 covered.
 *
 * <p>That concern is now resolved: the user confirmed against the real count168.com site (tenant 95,
 * XE account, many rows including the AG110 collision-group's MYR 145500 leg2) that Cr/Dr AMOUNT/SIGN
 * already matches between old and new for leg2 rows in their CURRENT (still-swapped) state — only the
 * FROM/TO description WORDING differs, which is expected and out of scope (old legacy RATE rows used a
 * frozen pre-"大优化" description format; the app now always renders the current dynamic per-viewer
 * format for every RATE row regardless of vintage, so old/new description text for legacy rows was
 * never going to literally match again, and that's fine). So the phase 1/2 rule generalizes cleanly:
 * leg1 always needs reverting to legacy-verbatim values, leg2 is always left as currently swapped,
 * regardless of 2-party/3-party structure or the leg1-vs-leg2 from_account_id collision.
 *
 * <p>Remaining review point (not blocking this data fix, tracked separately in MIGRATION_LOG.md §34):
 * 18 of these 28 groups have a middleman ("RATE") account. After this revert, leg1 and leg2 will share
 * the same {@code from_account_id} for the viewing account (e.g. AG110) in those groups —
 * {@link TransactionHistoryServiceImpl#mergeRateMiddlemanDeductionsIntoMainLeg} picks which leg a
 * middleman fee row merges into by matching {@code fromAccountId} against the viewed account, so this
 * should be spot-checked after applying (does the middleman/"RATE"-account fee line still merge into
 * the correct leg's Cr/Dr for AG110's own Payment History?).
 *
 * <p>Same fix mechanics as phases 1-2: restore leg1's {@code account_id}/{@code from_account_id} to the
 * exact values on record in the legacy staging DB; leg2 and all code untouched.
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateLeg1DirectionRevertPhase3Tool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--legacy-db=c168_net_legacy_20260827] [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class RateLeg1DirectionRevertPhase3Tool {

    private RateLeg1DirectionRevertPhase3Tool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String legacyDb = "c168_net_legacy_20260827";
        String reportPath = "rate_leg1_direction_revert_phase3_report.txt";
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

            // Scope: every remaining leg1 row still in the post-swap (wrong) state -- no collision
            // restriction this time, covers all of the previously-held-back 28.
            String scopeSql =
                    "SELECT tr.rate_group_id, tr.leg1_transaction_id AS txn_id, tr.middleman_account_id, "
                            + "       l1.account_id AS correct_account_id, l1.from_account_id AS correct_from_account_id, "
                            + "       t1.account_id AS current_account_id, t1.from_account_id AS current_from_account_id "
                            + "FROM transactions_rate tr "
                            + "JOIN transactions t1 ON t1.id = tr.leg1_transaction_id "
                            + "JOIN " + legacyDb + ".transactions l1 ON l1.id = tr.leg1_transaction_id "
                            + "WHERE tr.rate_group_id LIKE 'RATE\\_%' "
                            + "  AND NOT (t1.account_id = l1.account_id AND t1.from_account_id = l1.from_account_id)";

            int total = 0;
            int updated = 0;
            int withMiddleman = 0;
            try (ResultSet rs = st.executeQuery(scopeSql)) {
                while (rs.next()) {
                    total++;
                    int txnId = rs.getInt("txn_id");
                    int correctAccountId = rs.getInt("correct_account_id");
                    int correctFromAccountId = rs.getInt("correct_from_account_id");
                    int currentAccountId = rs.getInt("current_account_id");
                    int currentFromAccountId = rs.getInt("current_from_account_id");
                    boolean hasMiddleman = rs.getObject("middleman_account_id") != null;
                    if (hasMiddleman) {
                        withMiddleman++;
                    }

                    report.write(String.format(
                            "REVERT group=%s leg1_txn=%d%s  account_id %d->%d  from_account_id %d->%d",
                            rs.getString("rate_group_id"), txnId, hasMiddleman ? " [middleman]" : "",
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
                    "in_scope_leg1_rows=%d (with_middleman=%d) updated=%d mode=%s report=%s",
                    total, withMiddleman, updated, apply ? "APPLY" : "PREVIEW", reportPath);
            System.out.println(summary);
            report.write(summary);
            report.newLine();
        }
    }
}
