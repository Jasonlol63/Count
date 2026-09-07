package com.eazycount.service.impl;

import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BkProcessAccountingPosted;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * One-off, narrowly-scoped fix for the two {@code bank_process_accounting_posted} rows
 * (TRUSTY HAULERS PTE LTD / bank_process 457, SUPPER SERVICE PTE LTD / bank_process 458) whose
 * August period was mis-classified as {@code FULL_MONTH} instead of {@code DAY_END_TAIL} — a
 * pre-existing data problem from the original legacy migration, not something either of the two
 * earlier backfill tools touched (they never write {@code period_type}). The WIN/LOSS amount for
 * these rows was already correctly prorated (12 days); only the stored classification, and
 * therefore the regenerated description, was wrong.
 *
 * <p>Scope is hardcoded to exactly the two ledger ids identified and verified in the chat analysis
 * ({@code TARGET_LEDGER_IDS}) — this is deliberately not a generic "find and fix everywhere" tool,
 * to avoid touching the 3 other system-wide matches that have zero linked transactions and were
 * left alone, and to avoid re-deriving day-end-tail eligibility with a broader, unreviewed query.
 *
 * <p>For each target ledger row: sets {@code period_type = DAY_END_TAIL}, {@code billing_start =
 * posted_date}, {@code billing_end = bank_process.day_end} (mirrors the live due-generator's own
 * rule in {@code buildFirstOfMonthDueForMonth}), then regenerates the description for every linked
 * transaction via {@link BankAccountingDueServiceImpl#buildLineDescription}.
 *
 * <p>Preview-only by default; pass {@code --apply} to write.
 */
public final class BankProcessDayEndTailFixTool {

    private static final int[] TARGET_LEDGER_IDS = {1696, 1697};

    private BankProcessDayEndTailFixTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "day_end_tail_fix_report.txt";
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

        int ledgerFixed = 0;
        int descriptionChanged = 0;

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath))) {
            conn.setAutoCommit(false);

            try (PreparedStatement fetchLedger = conn.prepareStatement(
                    "SELECT bpap.id, bpap.posted_date, bp.day_end, bp.card_owner "
                            + "FROM bank_process_accounting_posted bpap "
                            + "JOIN bank_process bp ON bp.id = bpap.bank_process_id "
                            + "WHERE bpap.id = ?");
                 PreparedStatement updateLedger = conn.prepareStatement(
                         "UPDATE bank_process_accounting_posted "
                                 + "SET period_type = 'DAY_END_TAIL', billing_start = ?, billing_end = ? WHERE id = ?")) {

                for (int ledgerId : TARGET_LEDGER_IDS) {
                    fetchLedger.setInt(1, ledgerId);
                    LocalDate postedDate;
                    LocalDate dayEnd;
                    String cardOwner;
                    try (ResultSet rs = fetchLedger.executeQuery()) {
                        if (!rs.next()) {
                            report.write("SKIP ledger=" + ledgerId + " reason=not_found\n");
                            continue;
                        }
                        postedDate = rs.getDate("posted_date").toLocalDate();
                        dayEnd = rs.getDate("day_end").toLocalDate();
                        cardOwner = rs.getString("card_owner");
                    }
                    LocalDate billingStart = postedDate;
                    LocalDate billingEnd = dayEnd;

                    report.write("FIX ledger=" + ledgerId + " (" + cardOwner + ") period_type FULL_MONTH -> DAY_END_TAIL"
                            + " billing_start=" + billingStart + " billing_end=" + billingEnd + "\n");
                    ledgerFixed++;

                    // Always write within this (uncommitted) transaction so the description
                    // regeneration below sees the corrected period_type/dates; final COMMIT vs
                    // ROLLBACK is decided by --apply.
                    updateLedger.setDate(1, Date.valueOf(billingStart));
                    updateLedger.setDate(2, Date.valueOf(billingEnd));
                    updateLedger.setInt(3, ledgerId);
                    updateLedger.executeUpdate();
                }
            }

            descriptionChanged = regenerateDescriptions(conn, report, TARGET_LEDGER_IDS);

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }
        }

        System.out.println(String.format(
                "mode=%s report=%s ledger_fixed=%d description_changed=%d",
                apply ? "APPLY" : "PREVIEW", reportPath, ledgerFixed, descriptionChanged));
    }

    private static int regenerateDescriptions(Connection conn, BufferedWriter report, int[] ledgerIds)
            throws SQLException, java.io.IOException {
        Map<Integer, String> bankNameByOptionId = loadBankOptionNames(conn);
        StringBuilder ids = new StringBuilder();
        for (int id : ledgerIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }

        String sql = "SELECT t.id, t.account_id, t.amount, t.description AS old_description, "
                + "bpap.period_type, bpap.posted_date, bpap.billing_start, bpap.billing_end, "
                + "bp.id AS bp_id, bp.contract, bp.status, bp.frequency, bp.resend_schedule_frequency, bp.bank_option_id, "
                + "bp.supplier_account_id, bp.supplier_price, bp.customer_account_id, bp.customer_price, "
                + "bp.company_account_id, bp.company_price "
                + "FROM transactions t "
                + "JOIN bank_process_accounting_posted bpap ON bpap.id = t.bank_process_posted_id "
                + "JOIN bank_process bp ON bp.id = bpap.bank_process_id "
                + "WHERE t.bank_process_posted_id IN (" + ids + ")";

        int changed = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
             PreparedStatement updatePs = conn.prepareStatement("UPDATE transactions SET description = ? WHERE id = ?")) {

            while (rs.next()) {
                int txnId = rs.getInt("id");
                int accountId = rs.getInt("account_id");
                BigDecimal amount = rs.getBigDecimal("amount");
                String oldDescription = rs.getString("old_description");

                BkProcessAccountingPosted.PeriodType periodType =
                        BkProcessAccountingPosted.PeriodType.valueOf(rs.getString("period_type"));
                LocalDate postedDate = toLocalDate(rs.getDate("posted_date"));
                LocalDate billingStart = toLocalDate(rs.getDate("billing_start"));
                LocalDate billingEnd = toLocalDate(rs.getDate("billing_end"));

                BankProcess bp = new BankProcess();
                int bpId = rs.getInt("bp_id");
                bp.setId(bpId);
                bp.setContract(rs.getString("contract"));
                bp.setStatus(enumOrNull(BankProcess.Status.class, rs.getString("status")));
                bp.setFrequency(enumOrNull(BankProcess.Frequency.class, rs.getString("frequency")));
                bp.setResendScheduleFrequency(enumOrNull(BankProcess.Frequency.class, rs.getString("resend_schedule_frequency")));

                Integer bankOptionId = getNullableInt(rs, "bank_option_id");
                String bankName = bankOptionId != null ? bankNameByOptionId.getOrDefault(bankOptionId, "") : "";

                Integer supplierAccountId = getNullableInt(rs, "supplier_account_id");
                Integer customerAccountId = getNullableInt(rs, "customer_account_id");
                Integer companyAccountId = getNullableInt(rs, "company_account_id");

                boolean compensation = BankAccountingDueServiceImpl.isCompensationPost(bp, periodType);

                BigDecimal baseAmount;
                if (supplierAccountId != null && supplierAccountId == accountId) {
                    baseAmount = rs.getBigDecimal("supplier_price");
                } else if (customerAccountId != null && customerAccountId == accountId) {
                    baseAmount = rs.getBigDecimal("customer_price");
                } else if (companyAccountId != null && companyAccountId == accountId) {
                    baseAmount = rs.getBigDecimal("company_price");
                } else {
                    baseAmount = resolveShareAmount(conn, bpId, accountId);
                }

                if (!compensation && baseAmount == null) {
                    report.write("SKIP(description) txn=" + txnId + " reason=no_matching_leg_or_share\n");
                    continue;
                }

                String newDescription = BankAccountingDueServiceImpl.buildLineDescription(
                        bp, periodType, postedDate, billingStart, billingEnd,
                        nz(amount), nz(baseAmount), bankName, compensation);

                if (newDescription != null && newDescription.equals(oldDescription)) {
                    continue;
                }
                changed++;
                report.write("CHANGE(description) txn=" + txnId
                        + "\n  old=[" + oldDescription + "]\n  new=[" + newDescription + "]\n");
                updatePs.setString(1, newDescription);
                updatePs.setInt(2, txnId);
                updatePs.executeUpdate();
            }
        }
        return changed;
    }

    private static BigDecimal resolveShareAmount(Connection conn, int bankProcessId, int accountId) {
        String sql = "SELECT amount FROM bank_process_share WHERE bank_process_id = ? AND account_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bankProcessId);
            ps.setInt(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<Integer, String> loadBankOptionNames(Connection conn) throws SQLException {
        Map<Integer, String> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM bank_option");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getInt("id"), rs.getString("name"));
            }
        }
        return result;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toLocalDate(Date date) {
        return date != null ? date.toLocalDate() : null;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value != null && !value.isBlank() ? Enum.valueOf(type, value) : null;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
