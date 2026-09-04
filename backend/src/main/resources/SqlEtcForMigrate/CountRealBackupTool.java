package com.eazycount.service.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off ops utility: full refresh of {@code count_real_backup} from the current state of
 * {@code count_real} — drops and recreates every table in the backup schema as an exact copy
 * (structure + data) of the live schema. No mysqldump/mysql CLI available in this environment, so this
 * does the copy purely over JDBC: {@code CREATE TABLE ... LIKE} for structure, then
 * {@code INSERT INTO ... SELECT *} for data, per table, with FK checks disabled during the copy (the
 * two schemas are copied independently, so cross-table FK ordering doesn't matter here).
 *
 * <p>Requested explicitly by the user ("直接覆盖旧数据" — overwrite the old backup outright), after a
 * session's worth of live data corrections to {@code count_real} (see MIGRATION_LOG.md §30-§36) that
 * are worth snapshotting.
 *
 * <pre>
 *   java -cp &lt;classpath&gt; com.eazycount.service.impl.CountRealBackupTool \
 *       --url=jdbc:mysql://localhost:3306/?serverTimezone=Asia/Shanghai --user=root --password= \
 *       [--source=count_real] [--target=count_real_backup]
 * </pre>
 */
public final class CountRealBackupTool {

    private CountRealBackupTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String source = "count_real";
        String target = "count_real_backup";

        for (String arg : args) {
            if (arg.startsWith("--url=")) {
                url = arg.substring("--url=".length());
            } else if (arg.startsWith("--user=")) {
                user = arg.substring("--user=".length());
            } else if (arg.startsWith("--password=")) {
                password = arg.substring("--password=".length());
            } else if (arg.startsWith("--source=")) {
                source = arg.substring("--source=".length());
            } else if (arg.startsWith("--target=")) {
                target = arg.substring("--target=".length());
            }
        }

        Class.forName("com.mysql.cj.jdbc.Driver");
        // Long-lived single connections were getting dropped mid-run (server/network idle timeout) --
        // every DB operation below opens and closes its own short-lived connection instead.

        List<String> tables = new ArrayList<>();
        try (Connection conn = connect(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = '" + source + "' AND TABLE_TYPE = 'BASE TABLE' "
                             + "ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        System.out.println("Source tables found: " + tables.size());

        try (Connection conn = connect(url, user, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS `" + target + "`");
            st.executeUpdate("CREATE DATABASE `" + target + "` "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        System.out.println("Recreated database: " + target);

        int total = tables.size();
        int done = 0;
        for (String table : tables) {
            int attempts = 0;
            while (true) {
                attempts++;
                try (Connection conn = connect(url, user, password);
                     Statement st = conn.createStatement()) {
                    st.executeUpdate("SET FOREIGN_KEY_CHECKS=0");
                    st.executeUpdate("SET UNIQUE_CHECKS=0");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + target + "`.`" + table
                            + "` LIKE `" + source + "`.`" + table + "`");
                    st.executeUpdate("TRUNCATE TABLE `" + target + "`.`" + table + "`");
                    int rows = st.executeUpdate(
                            "INSERT INTO `" + target + "`.`" + table + "` SELECT * FROM `" + source + "`.`" + table + "`");
                    done++;
                    System.out.println(String.format("[%d/%d] %s: %d rows copied", done, total, table, rows));
                    break;
                } catch (Exception e) {
                    if (attempts >= 3) {
                        throw e;
                    }
                    System.out.println("Retrying " + table + " after error: " + e.getMessage());
                }
            }
        }

        // Verification: row counts must match exactly, table by table.
        System.out.println("---- Verifying row counts ----");
        int mismatches = 0;
        for (String table : tables) {
            long srcCount;
            long tgtCount;
            try (Connection conn = connect(url, user, password);
                 Statement st = conn.createStatement()) {
                srcCount = countRows(st, source, table);
                tgtCount = countRows(st, target, table);
            }
            if (srcCount != tgtCount) {
                mismatches++;
                System.out.println(String.format(
                        "MISMATCH %s: source=%d target=%d", table, srcCount, tgtCount));
            }
        }
        System.out.println(String.format(
                "Verification complete: %d tables checked, %d mismatches", tables.size(), mismatches));
    }

    private static Connection connect(String url, String user, String password) throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    private static long countRows(Statement st, String schema, String table) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
