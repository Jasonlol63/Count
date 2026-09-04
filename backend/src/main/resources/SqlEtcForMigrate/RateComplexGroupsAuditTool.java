package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * READ-ONLY audit tool (writes nothing) for the 28 remaining {@code rate_group_id LIKE 'RATE_%'}
 * groups not covered by {@link RateLeg1DirectionRevertTool} / {@link RateLeg1DirectionRevertPhase2Tool}
 * (see MIGRATION_LOG.md §34) — the ones where reverting leg1 alone would make its
 * {@code from_account_id} collide with leg2's current {@code from_account_id}.
 *
 * <p>Unlike the 154 already-fixed groups (simple two-party legs), a sample of these 28 (tenant AG,
 * accounts AG110/LOON and XE/CURRENCY, 18 with a "RATE" middleman account) turned out to be 3-party
 * structures where leg1 and leg2 don't share the same counterparty pair — so it's not yet established
 * whether leg2 should stay in its current (swapped) state, like the other 154 groups, or ALSO be
 * reverted to legacy-verbatim. This tool computes, for each group, what BOTH legs would render as
 * (description direction + Cr/Dr sign) under full legacy-verbatim values (no swap on either leg) from
 * the two "hub" accounts' viewpoints (AG110/LOON id=4641 and XE id=4580, the accounts common to nearly
 * all 28 groups) using the exact same formula the app already applies elsewhere
 * ({@link TransactionHistoryServiceImpl#applyRateHistoryPresentation}-equivalent logic, reproduced here
 * read-only) — so a human can directly cross-check this against the real count168.com page before any
 * fix is written.
 *
 * <p>Also lists any other RATE-type transactions sharing the same {@code rate_group_id} that are NOT
 * leg1/leg2 (i.e. the Rate-Mul/Service-Fee/Platform-Fee middleman rows), since 18 of these 28 groups
 * have a middleman and those extra rows need the same before/after comparison.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.RateComplexGroupsAuditTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--legacy-db=c168_net_legacy_20260827] [--report=xxx.txt]
 * </pre>
 */
public final class RateComplexGroupsAuditTool {

    private RateComplexGroupsAuditTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String legacyDb = "c168_net_legacy_20260827";
        String reportPath = "rate_complex_groups_audit.txt";

        for (String arg : args) {
            if (arg.startsWith("--url=")) {
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
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath))) {

            Map<Integer, String> accountCodes = loadAccountCodes(conn);

            String groupsSql =
                    "SELECT tr.rate_group_id, tr.leg1_transaction_id, tr.leg2_transaction_id, tr.middleman_account_id "
                            + "FROM transactions_rate tr "
                            + "JOIN " + legacyDb + ".transactions l1 ON l1.id = tr.leg1_transaction_id "
                            + "JOIN transactions t2 ON t2.id = tr.leg2_transaction_id "
                            + "WHERE tr.rate_group_id LIKE 'RATE\\_%' "
                            + "  AND l1.from_account_id = t2.from_account_id "
                            + "ORDER BY tr.rate_group_id";

            try (PreparedStatement ps = conn.prepareStatement(groupsSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String groupId = rs.getString("rate_group_id");
                    int leg1Id = rs.getInt("leg1_transaction_id");
                    int leg2Id = rs.getInt("leg2_transaction_id");
                    Integer middlemanAccountId = getNullableInt(rs, "middleman_account_id");

                    report.write("==== GROUP " + groupId
                            + (middlemanAccountId != null ? "  (middleman=" + code(accountCodes, middlemanAccountId) + ")" : "")
                            + " ====");
                    report.newLine();

                    TxnSnapshot leg1Legacy = loadTxn(conn, legacyDb + ".transactions", leg1Id);
                    TxnSnapshot leg1Current = loadTxn(conn, "transactions", leg1Id);
                    TxnSnapshot leg2Legacy = loadTxn(conn, legacyDb + ".transactions", leg2Id);
                    TxnSnapshot leg2Current = loadTxn(conn, "transactions", leg2Id);

                    writeLegLine(report, accountCodes, "leg1", leg1Id, leg1Legacy, leg1Current);
                    writeLegLine(report, accountCodes, "leg2", leg2Id, leg2Legacy, leg2Current);

                    // Any other RATE rows in the same group (middleman fee legs), current DB only
                    // (legacy DB has no rate_group_id column pre-migration shape here, so we only show
                    // current state + legacy raw account/from for reference by transaction id).
                    String otherSql =
                            "SELECT id, account_id, from_account_id, amount, currency_id, description "
                                    + "FROM transactions "
                                    + "WHERE rate_group_id = ? AND id NOT IN (?, ?)";
                    try (PreparedStatement ops = conn.prepareStatement(otherSql)) {
                        ops.setString(1, groupId);
                        ops.setInt(2, leg1Id);
                        ops.setInt(3, leg2Id);
                        try (ResultSet ors = ops.executeQuery()) {
                            while (ors.next()) {
                                int otherId = ors.getInt("id");
                                report.write(String.format(
                                        "  other txn=%d  account=%s  from=%s  amount=%s  desc=%s",
                                        otherId, code(accountCodes, ors.getInt("account_id")),
                                        code(accountCodes, ors.getInt("from_account_id")),
                                        ors.getBigDecimal("amount"), ors.getString("description")));
                                report.newLine();
                            }
                        }
                    }

                    // Predicted rendering for the two hub accounts, using LEGACY-VERBATIM values for
                    // BOTH legs (the hypothesis to verify against the live count168.com page).
                    for (int viewedId : new int[] {4641, 4580}) {
                        String viewedCode = code(accountCodes, viewedId);
                        report.write("  -- if BOTH legs use legacy-verbatim values, viewed from " + viewedCode + ":");
                        report.newLine();
                        writePrediction(report, accountCodes, "leg1", viewedId, leg1Legacy);
                        writePrediction(report, accountCodes, "leg2", viewedId, leg2Legacy);
                    }
                    report.newLine();
                }
            }

            System.out.println("Audit written to " + reportPath);
        }
    }

    private static void writeLegLine(BufferedWriter report, Map<Integer, String> codes, String label,
                                      int txnId, TxnSnapshot legacy, TxnSnapshot current) throws Exception {
        report.write(String.format(
                "%s txn=%d  currency=%s  amount=%s  |  legacy(account=%s, from=%s)  |  current(account=%s, from=%s)%s",
                label, txnId, legacy.currencyCode, legacy.amount,
                code(codes, legacy.accountId), code(codes, legacy.fromAccountId),
                code(codes, current.accountId), code(codes, current.fromAccountId),
                sameAsLegacy(legacy, current) ? "  [unchanged vs legacy]" : "  [SWAPPED vs legacy]"));
        report.newLine();
    }

    private static boolean sameAsLegacy(TxnSnapshot legacy, TxnSnapshot current) {
        return legacy.accountId == current.accountId && legacy.fromAccountId == current.fromAccountId;
    }

    /* Mirrors TransactionHistoryServiceImpl.applyRateHistoryPresentation's plain-formula branch. */
    private static void writePrediction(BufferedWriter report, Map<Integer, String> codes, String label,
                                         int viewedAccountId, TxnSnapshot t) throws Exception {
        if (t.accountId != viewedAccountId && t.fromAccountId != viewedAccountId) {
            report.write("     " + label + ": (viewed account not party to this leg)");
            report.newLine();
            return;
        }
        String text;
        BigDecimal signed;
        if (t.fromAccountId == viewedAccountId) {
            text = "TO " + code(codes, t.accountId);
            signed = t.amount;
        } else {
            text = "FROM " + code(codes, t.fromAccountId);
            signed = t.amount.negate();
        }
        report.write(String.format("     %s: %s  Cr/Dr=%s", label, text, signed));
        report.newLine();
    }

    private static String code(Map<Integer, String> codes, int accountId) {
        return codes.getOrDefault(accountId, "#" + accountId);
    }

    private static Map<Integer, String> loadAccountCodes(Connection conn) throws Exception {
        Map<Integer, String> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, account_id FROM account");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getInt("id"), rs.getString("account_id"));
            }
        }
        return result;
    }

    private static TxnSnapshot loadTxn(Connection conn, String table, int id) throws Exception {
        String sql = "SELECT t.account_id, t.from_account_id, t.amount, c.code AS currency_code "
                + "FROM " + table + " t JOIN currency c ON c.id = t.currency_id WHERE t.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                TxnSnapshot snap = new TxnSnapshot();
                snap.accountId = rs.getInt("account_id");
                snap.fromAccountId = rs.getInt("from_account_id");
                snap.amount = rs.getBigDecimal("amount");
                snap.currencyCode = rs.getString("currency_code");
                return snap;
            }
        }
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static final class TxnSnapshot {
        int accountId;
        int fromAccountId;
        BigDecimal amount;
        String currencyCode;
    }
}
