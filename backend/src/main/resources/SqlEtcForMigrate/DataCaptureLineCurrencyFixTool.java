package com.eazycount.service.impl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION (not raw migration): fixes {@code data_capture_line.currency_id} (and the
 * derived {@code transactions.currency_id} written by the §18 backfill,
 * {@code migrate_data_capture_line_transactions_backfill.sql}) for lines whose legacy per-line currency
 * differs from their capture header's currency.
 *
 * <p>Root cause: [{@code migrate_data_datacapture_from_legacy.sql}](migrate_data_datacapture_from_legacy.sql)
 * line ~123 populates {@code data_capture_line.currency_id} from {@code dc.currency_id} (the
 * already-migrated {@code data_captures} HEADER row), not from {@code dcd.currency_id} (the legacy
 * {@code data_capture_details} row's OWN currency column). Legacy schema allows these to differ — a
 * process/header can be denominated in one currency (e.g. a SGD-branded game vendor) while an
 * individual account's line is actually settled in a different currency (e.g. MYR, matching that
 * account's own configured currency) with {@code rate}/{@code rate_expression} recording the
 * conversion factor used to compute the formula. Confirmed 2,946 legacy
 * {@code data_capture_details} rows across multiple tenants have
 * {@code currency_id <> capture_header.currency_id}; all 2,946 got the wrong (header) currency in
 * {@code count_real.data_capture_line}, and — since §18's backfill derived
 * {@code transactions.currency_id} from {@code data_capture_line.currency_id} — the same wrong
 * currency propagated into the corresponding {@code transactions} rows too.
 *
 * <p>User-visible symptom: Payment History filters Data Capture lines by the viewed account's
 * configured currencies ({@code TransactionHistoryMapper.findDataCaptureHistoryLines} — see
 * {@code account_currency}). An account configured for MYR only, viewing a line that was wrongly
 * tagged SGD by this bug, never sees that row at all — it silently disappears from history instead of
 * showing under the wrong currency, which is what made this look like "migration dropped a record"
 * rather than "record has the wrong currency".
 *
 * <p>Fix: rebuild the same currency-dedup mapping
 * {@code migrate_data_datacapture_from_legacy.sql} step 0 uses (manual wins over subsidiary, lowest id
 * breaks ties within the same legacy company), re-derive the correct survivor currency id from each
 * legacy line's OWN {@code currency_id} (not the header's), and update both
 * {@code data_capture_line.currency_id} and the linked {@code transactions.currency_id} to match.
 * {@code data_capture_formula.currency_id} is NOT affected — that INSERT already correctly used its own
 * row's {@code currency_id} (see the migration script's step 3), this bug only ever hit step 2
 * (the data_capture_line INSERT).
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.DataCaptureLineCurrencyFixTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--legacy-db=c168_net_legacy_20260827] [--apply] [--report=xxx.txt]
 * </pre>
 */
public final class DataCaptureLineCurrencyFixTool {

    private DataCaptureLineCurrencyFixTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String legacyDb = "c168_net_legacy_20260827";
        String reportPath = "data_capture_line_currency_fix_report.txt";
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

            st.executeUpdate("CREATE TEMPORARY TABLE _map_currency_fix19 ("
                    + "old_currency_id INT NOT NULL PRIMARY KEY, survivor_id INT NOT NULL)");
            st.executeUpdate("INSERT INTO _map_currency_fix19 (old_currency_id, survivor_id) "
                    + "SELECT cu.id, s.id "
                    + "FROM " + legacyDb + ".currency cu "
                    + "JOIN (SELECT id, company_id, code, "
                    + "             ROW_NUMBER() OVER (PARTITION BY company_id, code "
                    + "                                 ORDER BY (sync_source = 'subsidiary'), id) AS rn "
                    + "      FROM " + legacyDb + ".currency) s "
                    + "  ON s.company_id = cu.company_id AND s.code = cu.code "
                    + "WHERE s.rn = 1");

            int mismatchTotal;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM " + legacyDb + ".data_capture_details dcd "
                            + "JOIN " + legacyDb + ".data_captures dc ON dc.id = dcd.capture_id "
                            + "WHERE dcd.currency_id <> dc.currency_id")) {
                rs.next();
                mismatchTotal = rs.getInt(1);
            }
            report.write("legacy_mismatched_lines (currency_id <> header currency_id) = " + mismatchTotal);
            report.newLine();

            // Preview / audit trail: exact before/after currency codes and row counts.
            try (ResultSet rs = st.executeQuery(
                    "SELECT mc.survivor_id AS correct_id, cc.code AS correct_code, "
                            + "       dcl.currency_id AS current_id, wc.code AS current_code, COUNT(*) AS n "
                            + "FROM " + legacyDb + ".data_capture_details dcd "
                            + "JOIN " + legacyDb + ".data_captures dc ON dc.id = dcd.capture_id "
                            + "JOIN data_capture_line dcl ON dcl.id = dcd.id "
                            + "JOIN _map_currency_fix19 mc ON mc.old_currency_id = dcd.currency_id "
                            + "JOIN currency cc ON cc.id = mc.survivor_id "
                            + "JOIN currency wc ON wc.id = dcl.currency_id "
                            + "WHERE dcd.currency_id <> dc.currency_id "
                            + "GROUP BY mc.survivor_id, cc.code, dcl.currency_id, wc.code "
                            + "ORDER BY n DESC")) {
                while (rs.next()) {
                    report.write(String.format("  %s (id=%d) -> %s (id=%d): %d rows",
                            rs.getString("current_code"), rs.getInt("current_id"),
                            rs.getString("correct_code"), rs.getInt("correct_id"), rs.getInt("n")));
                    report.newLine();
                }
            }

            int lineRowsToFix;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM " + legacyDb + ".data_capture_details dcd "
                            + "JOIN " + legacyDb + ".data_captures dc ON dc.id = dcd.capture_id "
                            + "JOIN data_capture_line dcl ON dcl.id = dcd.id "
                            + "JOIN _map_currency_fix19 mc ON mc.old_currency_id = dcd.currency_id "
                            + "WHERE dcd.currency_id <> dc.currency_id AND dcl.currency_id <> mc.survivor_id")) {
                rs.next();
                lineRowsToFix = rs.getInt(1);
            }

            int lineRowsUpdated = 0;
            int txnRowsUpdated = 0;
            if (apply) {
                lineRowsUpdated = st.executeUpdate(
                        "UPDATE data_capture_line dcl "
                                + "JOIN " + legacyDb + ".data_capture_details dcd ON dcd.id = dcl.id "
                                + "JOIN " + legacyDb + ".data_captures dc ON dc.id = dcd.capture_id "
                                + "JOIN _map_currency_fix19 mc ON mc.old_currency_id = dcd.currency_id "
                                + "SET dcl.currency_id = mc.survivor_id "
                                + "WHERE dcd.currency_id <> dc.currency_id AND dcl.currency_id <> mc.survivor_id");

                txnRowsUpdated = st.executeUpdate(
                        "UPDATE transactions t "
                                + "JOIN data_capture_line dcl ON dcl.transaction_id = t.id "
                                + "SET t.currency_id = dcl.currency_id "
                                + "WHERE dcl.currency_id <> t.currency_id");
            }

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }

            String summary = String.format(
                    "legacy_mismatched=%d line_rows_needing_fix=%d line_rows_updated=%d txn_rows_updated=%d "
                            + "mode=%s report=%s",
                    mismatchTotal, lineRowsToFix, lineRowsUpdated, txnRowsUpdated,
                    apply ? "APPLY" : "PREVIEW", reportPath);
            System.out.println(summary);
            report.write(summary);
            report.newLine();
        }
    }
}
