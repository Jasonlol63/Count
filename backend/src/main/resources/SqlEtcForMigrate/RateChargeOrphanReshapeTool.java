package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off DATA CORRECTION: reshapes the legacy single-sided "Rate charge (xN) from CCY amount" pairs
 * (already linked to their {@code rate_group_id} by {@code RateChargeOrphanBackfillTool}) into the
 * SAME shape the current app writes for a freshly-submitted middleman fee leg -- one row with
 * {@code account_id} = the paying counterpart, {@code from_account_id} = the middleman account --
 * instead of two independent single-sided rows (one on the middleman's own account, one on the
 * counterpart's). See MIGRATION_LOG.md §34 follow-up.
 *
 * <p>Why: with {@code from_account_id} still NULL, {@code TransactionHistoryServiceImpl#toHistoryRow}
 * treats each of these rows as a single-sided "Platform Fee" ({@code isPlatformFee = isRateMiddlemanFee
 * && fromAccountId == null}) -- wrong on both sides: the middleman's own copy renders under Cr/Dr as a
 * NEGATIVE number with the raw un-recomputed legacy text (confirmed against a live screenshot: RATE
 * account showed "Fee" product, negative Cr/Dr, stagnant "RATE CHARGE (X0.2)..." text -- count168.com
 * shows these as POSITIVE Win/Loss under a recomputed "MARKUP ..." description). Once reshaped into one
 * proper two-sided row, {@code isPlatformFee} becomes false and the existing (unmodified)
 * {@code applyRateMiddlemanHistoryPresentation}/Win-Loss-column logic renders it correctly for BOTH the
 * counterpart's view (Cr/Dr, negative, via {@code findDomainPaymentHistoryLines}'s from_account_id
 * branch) and the middleman's view (Win/Loss, positive, recomputed "MARKUP" text) -- no code changes at
 * all, this is purely a data-shape fix.
 *
 * <p>Scope (verified read-only before writing this tool): of the 47 rows backfilled with
 * {@code rate_group_id}, they group into 24 pairs (one is a lone singleton, tenant CX, id 17756 --
 * excluded, no counterpart found) sharing a {@code rate_group_id}. Of those 24:
 * <ul>
 *   <li>22 pairs: clean -- exactly one side's {@code account_id} equals the group's
 *       {@code transactions_rate.middleman_account_id}, amounts match exactly.</li>
 *   <li>1 pair (tenant AG, ids 17818/17819, amounts 7979.99997900 vs 7979.99998000): a 1e-8 rounding
 *       difference, not a real mismatch -- treated as matching within a small tolerance.</li>
 *   <li>1 pair (tenant 95, ids 2916/2917, group RATE_1773841967_5105): the group's
 *       {@code middleman_account_id} is NULL in {@code transactions_rate} -- a separate small migration
 *       gap on that one row (this group's leg1/leg2 clearly involve a real middleman in the underlying
 *       data, per the matching "Rate charge (x0.033) from CNY 3298.20" pair, it just never got recorded
 *       on the header). This tool ALSO backfills that one {@code middleman_account_id} (from the
 *       PROFIT-role side of the pair) before reshaping, since the reshape depends on it.</li>
 *   <li>1 pair (tenant BK1, ids 18146/18147, amounts 108.50 vs 110.00): a REAL amount mismatch, not
 *       rounding -- excluded, needs manual review.</li>
 * </ul>
 * Net: 22 + 1 (rounding) + 1 (middleman backfilled) = 24 pairs processed by this tool; 1 singleton + 1
 * real-mismatch pair explicitly left alone.
 *
 * <p>Per processed pair: UPDATE the counterpart row's {@code from_account_id} to the middleman account
 * id, then DELETE the now-redundant middleman-side duplicate row (keeping both would double-count).
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only -- prints exactly what it would
 * update/delete without writing.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateChargeOrphanReshapeTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class RateChargeOrphanReshapeTool {

    private RateChargeOrphanReshapeTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "rate_charge_orphan_reshape_report.txt";
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

            // Group the 47 backfilled "Rate charge" rows by rate_group_id.
            String groupSql =
                    "SELECT t.rate_group_id, tr.middleman_account_id, "
                            + "       GROUP_CONCAT(t.id ORDER BY t.id) AS txn_ids, "
                            + "       COUNT(*) AS n "
                            + "FROM transactions t "
                            + "JOIN transactions_rate tr ON tr.tenant_id = t.tenant_id AND tr.rate_group_id = t.rate_group_id "
                            + "WHERE t.transaction_type = 'RATE' AND t.description LIKE 'Rate charge%' "
                            + "  AND t.rate_group_id IS NOT NULL "
                            + "GROUP BY t.rate_group_id, tr.middleman_account_id "
                            + "HAVING n = 2";

            List<String> groupIds = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(groupSql)) {
                while (rs.next()) {
                    groupIds.add(rs.getString("rate_group_id"));
                }
            }

            int processed = 0;
            int skipped = 0;
            int middlemanBackfilled = 0;
            int updated = 0;
            int deleted = 0;

            for (String groupId : groupIds) {
                // Load the 2 rows + group's middleman_account_id + PROFIT-role account fallback.
                String rowsSql =
                        "SELECT t.id, t.account_id, t.amount, a.role, tr.middleman_account_id "
                                + "FROM transactions t "
                                + "JOIN account a ON a.id = t.account_id "
                                + "JOIN transactions_rate tr ON tr.tenant_id = t.tenant_id AND tr.rate_group_id = t.rate_group_id "
                                + "WHERE t.rate_group_id = ? AND t.transaction_type = 'RATE' "
                                + "  AND t.description LIKE 'Rate charge%' "
                                + "ORDER BY t.id";

                Integer rowAId = null;
                Integer rowAAccountId = null;
                BigDecimal rowAAmount = null;
                String rowARole = null;
                Integer rowBId = null;
                Integer rowBAccountId = null;
                BigDecimal rowBAmount = null;
                String rowBRole = null;
                Integer middlemanAccountId = null;

                try (PreparedStatement ps = conn.prepareStatement(rowsSql)) {
                    ps.setString(1, groupId);
                    try (ResultSet rs = ps.executeQuery()) {
                        int i = 0;
                        while (rs.next()) {
                            i++;
                            int id = rs.getInt("id");
                            int accId = rs.getInt("account_id");
                            BigDecimal amt = rs.getBigDecimal("amount");
                            String role = rs.getString("role");
                            Object mm = rs.getObject("middleman_account_id");
                            if (mm != null) {
                                middlemanAccountId = ((Number) mm).intValue();
                            }
                            if (i == 1) {
                                rowAId = id;
                                rowAAccountId = accId;
                                rowAAmount = amt;
                                rowARole = role;
                            } else {
                                rowBId = id;
                                rowBAccountId = accId;
                                rowBAmount = amt;
                                rowBRole = role;
                            }
                        }
                    }
                }

                if (rowAId == null || rowBId == null) {
                    skipped++;
                    report.write("SKIP group=" + groupId + " reason=not_exactly_2_rows");
                    report.newLine();
                    continue;
                }

                BigDecimal diff = rowAAmount.subtract(rowBAmount).abs();
                if (diff.compareTo(new BigDecimal("0.01")) > 0) {
                    skipped++;
                    report.write(String.format(
                            "SKIP group=%s reason=amount_mismatch a=%s(%s) b=%s(%s) diff=%s",
                            groupId, rowAId, rowAAmount, rowBId, rowBAmount, diff));
                    report.newLine();
                    continue;
                }

                // Determine which side is the middleman side.
                Integer resolvedMiddlemanAccountId = middlemanAccountId;
                Integer middlemanRowId;
                Integer middlemanRowAccountId;
                Integer counterpartRowId;

                if (resolvedMiddlemanAccountId != null && resolvedMiddlemanAccountId.equals(rowAAccountId)) {
                    middlemanRowId = rowAId;
                    middlemanRowAccountId = rowAAccountId;
                    counterpartRowId = rowBId;
                } else if (resolvedMiddlemanAccountId != null && resolvedMiddlemanAccountId.equals(rowBAccountId)) {
                    middlemanRowId = rowBId;
                    middlemanRowAccountId = rowBAccountId;
                    counterpartRowId = rowAId;
                } else if (resolvedMiddlemanAccountId == null && "PROFIT".equalsIgnoreCase(rowARole)
                        && !"PROFIT".equalsIgnoreCase(rowBRole)) {
                    middlemanRowId = rowAId;
                    middlemanRowAccountId = rowAAccountId;
                    counterpartRowId = rowBId;
                    resolvedMiddlemanAccountId = rowAAccountId;
                } else if (resolvedMiddlemanAccountId == null && "PROFIT".equalsIgnoreCase(rowBRole)
                        && !"PROFIT".equalsIgnoreCase(rowARole)) {
                    middlemanRowId = rowBId;
                    middlemanRowAccountId = rowBAccountId;
                    counterpartRowId = rowAId;
                    resolvedMiddlemanAccountId = rowBAccountId;
                } else {
                    skipped++;
                    report.write("SKIP group=" + groupId
                            + " reason=cannot_determine_middleman_side middleman_account_id="
                            + middlemanAccountId + " rowA=" + rowAAccountId + "(" + rowARole + ")"
                            + " rowB=" + rowBAccountId + "(" + rowBRole + ")");
                    report.newLine();
                    continue;
                }

                processed++;
                boolean needsMiddlemanBackfill = middlemanAccountId == null;
                if (needsMiddlemanBackfill) {
                    middlemanBackfilled++;
                }

                report.write(String.format(
                        "RESHAPE group=%s middleman_row=%d(account=%d) counterpart_row=%d "
                                + "-> set counterpart.from_account_id=%d%s, delete middleman_row",
                        groupId, middlemanRowId, middlemanRowAccountId, counterpartRowId,
                        resolvedMiddlemanAccountId,
                        needsMiddlemanBackfill ? " [+ backfill transactions_rate.middleman_account_id]" : ""));
                report.newLine();

                if (apply) {
                    if (needsMiddlemanBackfill) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE transactions_rate SET middleman_account_id = ? WHERE rate_group_id = ?")) {
                            ps.setInt(1, resolvedMiddlemanAccountId);
                            ps.setString(2, groupId);
                            ps.executeUpdate();
                        }
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE transactions SET from_account_id = ? WHERE id = ?")) {
                        ps.setInt(1, resolvedMiddlemanAccountId);
                        ps.setInt(2, counterpartRowId);
                        updated += ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM transactions WHERE id = ?")) {
                        ps.setInt(1, middlemanRowId);
                        deleted += ps.executeUpdate();
                    }
                }
            }

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }

            String summary = String.format(
                    "groups_seen=%d processed=%d skipped=%d middleman_backfilled=%d updated=%d deleted=%d mode=%s report=%s",
                    groupIds.size(), processed, skipped, middlemanBackfilled, updated, deleted,
                    apply ? "APPLY" : "PREVIEW", reportPath);
            System.out.println(summary);
            report.write(summary);
            report.newLine();
        }
    }
}
