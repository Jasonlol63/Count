package com.eazycount.service.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off DATA CORRECTION: normalizes the single {@code account.account_id} row that was saved in
 * lowercase ({@code id=5584}, {@code 'jb-tiger'} -> {@code 'JB-TIGER'}).
 *
 * <p>Confirmed pre-existing legacy data, not a migration artifact — the legacy PHP
 * {@code c168_net_legacy_20260827.account} row for this same id already had the lowercase value, and
 * the migration copied it verbatim (as it should for every other correctly-cased row). A full-table
 * case-sensitive scan ({@code WHERE BINARY account_id <> BINARY UPPER(account_id)}) found this is the
 * ONLY row in the entire table that isn't already uppercase — nothing else needs touching. No backend
 * code path (old or new) ever enforced uppercase server-side; every other account code stayed uppercase
 * purely because the account-creation UI transformed it client-side, and this one row slipped through
 * whatever created it (bulk import or an older form, most likely).
 *
 * <p>Safe: {@code account_id} is a display/business code, not the join key — {@code account.id} (the
 * numeric PK) is what every FK reference uses, so renaming this string cannot break any relationship.
 *
 * <p>Standalone JDBC tool (no Spring context). Default is preview-only.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.AccountIdCaseFixTool \
 *       --url=jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--apply]
 * </pre>
 */
public final class AccountIdCaseFixTool {

    private AccountIdCaseFixTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
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
            }
        }

        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);

            int mismatched;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM account WHERE BINARY account_id <> BINARY UPPER(account_id)")) {
                rs.next();
                mismatched = rs.getInt(1);
            }

            int updated = 0;
            if (apply) {
                updated = st.executeUpdate(
                        "UPDATE account SET account_id = 'JB-TIGER' "
                                + "WHERE id = 5584 AND BINARY account_id = BINARY 'jb-tiger'");
            }

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }

            System.out.println(String.format(
                    "non_uppercase_account_ids_before=%d updated=%d mode=%s",
                    mismatched, updated, apply ? "APPLY" : "PREVIEW"));
        }
    }
}
