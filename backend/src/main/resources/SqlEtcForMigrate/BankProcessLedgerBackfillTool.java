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
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second-pass backfill, building on {@link BankProcessDescriptionBackfillTool}:
 *
 * <p><b>Phase 1</b> — fills {@code bank_process_accounting_posted.billing_start}/{@code billing_end}
 * for the ~74 rows the first tool had to skip (already linked, but the §13 migration script never
 * populated those two columns). {@code billing_start} always equals {@code posted_date} (verified
 * invariant: {@link BankAccountingDueServiceImpl#buildFirstOfMonthDueForMonth},
 * {@code BankProcessResendServiceImpl#resolveWindow}). {@code billing_end} is derived per period type:
 * PARTIAL_FIRST_MONTH/DAY_END_TAIL from {@code bank_process.day_start}/{@code day_end} (same rule the
 * live due-generator uses); RESEND_CONSOLIDATED from the {@code [RESEND_END=yyyy-mm-dd]} marker still
 * present in the row's own untouched legacy description text.
 *
 * <p>Excludes {@code bank_process_id = 469} on purpose — those 3 rows already carry new-format
 * description text despite missing billing dates, a separate live-system anomaly left untouched here.
 *
 * <p><b>Phase 2</b> — links the 15 "orphan" transactions (no {@code bank_process_posted_id} at all)
 * identified and manually verified against {@code bank_process} (account id + price + card_owner
 * text cross-checked) to their bank process, by creating the missing
 * {@code bank_process_accounting_posted} ledger row and setting the FK. Period type comes from the
 * legacy description suffix; billing_end from the {@code [RESEND_END=...]} marker where present, or by
 * chaining to the next known posting for the same bank_process (its {@code posted_date} minus one day).
 *
 * <p><b>Phase 3</b> — re-runs the same description regeneration as
 * {@link BankProcessDescriptionBackfillTool} for every row touched in phases 1 and 2.
 *
 * <p>Preview-only by default; pass {@code --apply} to write.
 */
public final class BankProcessLedgerBackfillTool {

    private static final Pattern RESEND_END_PATTERN = Pattern.compile("\\[RESEND_END=(\\d{4}-\\d{2}-\\d{2})\\]");

    /** Manually verified txnId -> bankProcessId map for the 15 orphan rows (see chat analysis). */
    private static final Map<Integer, Integer> ORPHAN_BANK_PROCESS = new LinkedHashMap<>();

    static {
        for (int id : new int[]{6563, 6564, 6565, 6566, 6567, 6568, 8196, 8197, 8198}) {
            ORPHAN_BANK_PROCESS.put(id, 189); // TRAVELMINI SDN BHD
        }
        for (int id : new int[]{8328, 8329, 8330}) {
            ORPHAN_BANK_PROCESS.put(id, 420); // SUPPER SERVICE PTE.LTD
        }
        for (int id : new int[]{10481, 10482, 10483}) {
            ORPHAN_BANK_PROCESS.put(id, 526); // CARGO SOLUTIONS PTE LTD
        }
    }

    private BankProcessLedgerBackfillTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/count_real?serverTimezone=Asia/Shanghai";
        String user = "root";
        String password = "";
        String reportPath = "ledger_backfill_report.txt";
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

        List<Integer> touchedPostedIds = new ArrayList<>();
        int[] phase1Counts = new int[3]; // fixed, unresolved, excluded
        int[] phase2Counts = new int[2]; // events created, txns linked

        try (Connection conn = DriverManager.getConnection(url, user, password);
             BufferedWriter report = new BufferedWriter(new FileWriter(reportPath))) {
            conn.setAutoCommit(false);

            phase1FillMissingBillingDates(conn, report, apply, touchedPostedIds, phase1Counts);
            phase2LinkOrphans(conn, report, apply, touchedPostedIds, phase2Counts);

            int[] phase3Counts = new int[3]; // changed, unchanged, skipped
            phase3RegenerateDescriptions(conn, report, apply, touchedPostedIds, phase3Counts);

            if (apply) {
                conn.commit();
            } else {
                conn.rollback();
            }

            String summary = String.format(
                    "mode=%s report=%s%n"
                            + "phase1(billing dates): fixed=%d unresolved=%d excluded(bp=469)=%d%n"
                            + "phase2(orphan link): events_created=%d txns_linked=%d%n"
                            + "phase3(description): changed=%d unchanged=%d skipped=%d",
                    apply ? "APPLY" : "PREVIEW", reportPath,
                    phase1Counts[0], phase1Counts[1], phase1Counts[2],
                    phase2Counts[0], phase2Counts[1],
                    phase3Counts[0], phase3Counts[1], phase3Counts[2]);
            System.out.println(summary);
        }
    }

    // ── Phase 1 ──────────────────────────────────────────────────────────────
    private static void phase1FillMissingBillingDates(Connection conn, BufferedWriter report, boolean apply,
                                                        List<Integer> touchedPostedIds, int[] counts) throws SQLException, java.io.IOException {
        String sql = "SELECT bpap.id, bpap.bank_process_id, bpap.period_type, bpap.posted_date, "
                + "bp.day_start, bp.day_end, bp.day_end_monthly_cap_enabled, bp.expired_at_creation, "
                + "(SELECT t.description FROM transactions t WHERE t.bank_process_posted_id = bpap.id "
                + " AND t.description IS NOT NULL ORDER BY t.id LIMIT 1) AS sample_description "
                + "FROM bank_process_accounting_posted bpap "
                + "JOIN bank_process bp ON bp.id = bpap.bank_process_id "
                + "WHERE bpap.billing_start IS NULL "
                + "AND bpap.outcome = 'POSTED' "
                + "AND bpap.period_type IN ('PARTIAL_FIRST_MONTH','DAY_END_TAIL','RESEND_CONSOLIDATED') "
                + "ORDER BY bpap.bank_process_id, bpap.posted_date";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
             PreparedStatement updatePs = conn.prepareStatement(
                     "UPDATE bank_process_accounting_posted SET billing_start = ?, billing_end = ? WHERE id = ?")) {

            while (rs.next()) {
                int postedId = rs.getInt("id");
                int bankProcessId = rs.getInt("bank_process_id");
                if (bankProcessId == 469) {
                    counts[2]++;
                    report.write("EXCLUDE ledger=" + postedId + " bankProcess=469 (already new-format description, separate live bug)\n");
                    continue;
                }
                BkProcessAccountingPosted.PeriodType periodType =
                        BkProcessAccountingPosted.PeriodType.valueOf(rs.getString("period_type"));
                LocalDate postedDate = toLocalDate(rs.getDate("posted_date"));
                LocalDate dayEnd = toLocalDate(rs.getDate("day_end"));
                boolean useDayEndTail = rs.getBoolean("day_end_monthly_cap_enabled") || rs.getBoolean("expired_at_creation");
                String sampleDescription = rs.getString("sample_description");

                LocalDate billingStart = postedDate;
                LocalDate billingEnd;

                if (periodType == BkProcessAccountingPosted.PeriodType.DAY_END_TAIL) {
                    billingEnd = dayEnd;
                } else if (periodType == BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH) {
                    YearMonth startMonth = YearMonth.from(postedDate);
                    YearMonth endMonth = dayEnd != null ? YearMonth.from(dayEnd) : startMonth;
                    LocalDate monthEnd = startMonth.atEndOfMonth();
                    if (startMonth.equals(endMonth) && !useDayEndTail) {
                        billingEnd = monthEnd;
                    } else {
                        billingEnd = (dayEnd != null && dayEnd.isBefore(monthEnd)) ? dayEnd : monthEnd;
                    }
                } else { // RESEND_CONSOLIDATED
                    LocalDate marker = parseResendEnd(sampleDescription);
                    if (marker == null) {
                        counts[1]++;
                        report.write("UNRESOLVED ledger=" + postedId + " bankProcess=" + bankProcessId
                                + " periodType=RESEND_CONSOLIDATED reason=no_RESEND_END_marker description=[" + sampleDescription + "]\n");
                        continue;
                    }
                    billingEnd = marker;
                }

                counts[0]++;
                touchedPostedIds.add(postedId);
                report.write("FIX ledger=" + postedId + " bankProcess=" + bankProcessId + " periodType=" + periodType
                        + " billing_start=" + billingStart + " billing_end=" + billingEnd + "\n");

                // Always write within this (uncommitted) transaction so Phase 3 can preview the
                // resulting description correctly; the outer main() only COMMITs when --apply is set,
                // otherwise everything here is rolled back at the end.
                updatePs.setDate(1, Date.valueOf(billingStart));
                updatePs.setDate(2, Date.valueOf(billingEnd));
                updatePs.setInt(3, postedId);
                updatePs.executeUpdate();
            }
        }
    }

    // ── Phase 2 ──────────────────────────────────────────────────────────────
    private static void phase2LinkOrphans(Connection conn, BufferedWriter report, boolean apply,
                                          List<Integer> touchedPostedIds, int[] counts) throws SQLException, java.io.IOException {
        if (ORPHAN_BANK_PROCESS.isEmpty()) {
            return;
        }
        String ids = String.join(",", ORPHAN_BANK_PROCESS.keySet().stream().map(String::valueOf).toList());
        // bank_process_posted_id IS NULL guard added 2026-09-03: a fresh run against a newer legacy
        // snapshot resolved more of the regular pipeline's orphans naturally than the original run did
        // (source data differs), so some of these 15 hardcoded ids are no longer actually orphaned --
        // without this guard the tool tried to create a second, colliding ledger row for one already
        // linked by the regular migration (UNIQUE constraint violation on bank_process_accounting_
        // posted). This is a targeted, manually-verified list either way; skipping an id that's already
        // linked is correct, not a silent data loss.
        String sql = "SELECT t.id, t.tenant_id, t.account_id, t.amount, t.transaction_date, t.description "
                + "FROM transactions t WHERE t.id IN (" + ids + ") AND t.bank_process_posted_id IS NULL "
                + "ORDER BY t.transaction_date, t.id";

        // Group orphan rows into events keyed by (bankProcessId, transactionDate).
        Map<String, List<Map<String, Object>>> events = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int txnId = rs.getInt("id");
                Integer bankProcessId = ORPHAN_BANK_PROCESS.get(txnId);
                LocalDate txnDate = toLocalDate(rs.getDate("transaction_date"));
                String key = bankProcessId + "|" + txnDate;
                Map<String, Object> row = new HashMap<>();
                row.put("id", txnId);
                row.put("tenantId", rs.getInt("tenant_id"));
                row.put("accountId", rs.getInt("account_id"));
                row.put("amount", rs.getBigDecimal("amount"));
                row.put("transactionDate", txnDate);
                row.put("description", rs.getString("description"));
                row.put("bankProcessId", bankProcessId);
                events.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        // Sibling billing_start dates per bank_process, known up-front (no DB round trip needed since
        // billing_start == the orphan's own transaction_date) — needed so an earlier event chains to a
        // *later orphan event* being created in this same run, not just to whatever already exists in
        // bank_process_accounting_posted (which would overshoot past intervening orphan events).
        Map<Integer, List<LocalDate>> siblingStartsByBankProcess = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : events.entrySet()) {
            Map<String, Object> f = e.getValue().get(0);
            siblingStartsByBankProcess
                    .computeIfAbsent((Integer) f.get("bankProcessId"), k -> new ArrayList<>())
                    .add((LocalDate) f.get("transactionDate"));
        }

        try (PreparedStatement insertLedger = conn.prepareStatement(
                "INSERT INTO bank_process_accounting_posted "
                        + "(tenant_id, bank_process_id, posted_date, period_type, outcome, billing_start, billing_end, created_by) "
                        + "VALUES (?, ?, ?, ?, 'POSTED', ?, ?, 'LEDGER_BACKFILL_TOOL')",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement linkTxn = conn.prepareStatement(
                     "UPDATE transactions SET bank_process_posted_id = ? WHERE id = ?")) {

            for (Map.Entry<String, List<Map<String, Object>>> entry : events.entrySet()) {
                List<Map<String, Object>> rows = entry.getValue();
                Map<String, Object> first = rows.get(0);
                int bankProcessId = (Integer) first.get("bankProcessId");
                int tenantId = (Integer) first.get("tenantId");
                LocalDate transactionDate = (LocalDate) first.get("transactionDate");
                String description = (String) first.get("description");

                BkProcessAccountingPosted.PeriodType periodType = classifyPeriodType(conn, description, bankProcessId, transactionDate);
                LocalDate billingStart = transactionDate;
                LocalDate billingEnd;
                LocalDate marker = parseResendEnd(description);

                if (marker != null) {
                    billingEnd = marker;
                } else if (periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED) {
                    LocalDate next = findNextAnchor(conn, bankProcessId, transactionDate,
                            siblingStartsByBankProcess.get(bankProcessId));
                    if (next == null) {
                        report.write("UNRESOLVED orphan_event bankProcess=" + bankProcessId + " date=" + transactionDate
                                + " reason=no_marker_and_no_next_anchor txns=" + txnIdsOf(rows) + "\n");
                        continue;
                    }
                    billingEnd = next.minusDays(1);
                } else if (periodType == BkProcessAccountingPosted.PeriodType.MONTHLY) {
                    billingEnd = billingStart.plusMonths(1);
                } else {
                    billingEnd = YearMonth.from(billingStart).atEndOfMonth();
                }

                // Collision guard added 2026-09-03: a fresh run against a newer legacy snapshot found
                // this exact (tenant, bank_process, date, period_type) slot already occupied by an
                // outcome=SKIPPED row from the regular migration pipeline (the schema's UNIQUE key
                // doesn't include outcome, so a POSTED and a SKIPPED row can never coexist for the same
                // slot). This wasn't hit in the original run this tool's hardcoded orphan list was
                // verified against -- the underlying legacy record's date apparently differs between
                // snapshots. Rather than guess whether the existing SKIPPED row should be overwritten
                // (a real financial-classification decision), this event is logged UNRESOLVED and
                // skipped so the rest of Phase 2 can still complete; report and decide by hand.
                Integer existingId = null;
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT id FROM bank_process_accounting_posted "
                                + "WHERE tenant_id=? AND bank_process_id=? AND posted_date=? AND period_type=?")) {
                    check.setInt(1, tenantId);
                    check.setInt(2, bankProcessId);
                    check.setDate(3, Date.valueOf(billingStart));
                    check.setString(4, periodType.name());
                    try (ResultSet rsCheck = check.executeQuery()) {
                        if (rsCheck.next()) {
                            existingId = rsCheck.getInt("id");
                        }
                    }
                }
                if (existingId != null) {
                    report.write("UNRESOLVED orphan_event bankProcess=" + bankProcessId + " date=" + transactionDate
                            + " periodType=" + periodType + " reason=slot_occupied_by_existing_id=" + existingId
                            + " txns=" + txnIdsOf(rows) + "\n");
                    continue;
                }

                counts[0]++;
                report.write("LINK bankProcess=" + bankProcessId + " tenant=" + tenantId + " date=" + transactionDate
                        + " periodType=" + periodType + " billing_start=" + billingStart + " billing_end=" + billingEnd
                        + " txns=" + txnIdsOf(rows) + "\n");

                // Always write within this (uncommitted) transaction — see note in Phase 1 above.
                insertLedger.setInt(1, tenantId);
                insertLedger.setInt(2, bankProcessId);
                insertLedger.setDate(3, Date.valueOf(billingStart));
                insertLedger.setString(4, periodType.name());
                insertLedger.setDate(5, Date.valueOf(billingStart));
                insertLedger.setDate(6, Date.valueOf(billingEnd));
                insertLedger.executeUpdate();
                int newPostedId;
                try (ResultSet keys = insertLedger.getGeneratedKeys()) {
                    keys.next();
                    newPostedId = keys.getInt(1);
                }
                for (Map<String, Object> row : rows) {
                    linkTxn.setInt(1, newPostedId);
                    linkTxn.setInt(2, (Integer) row.get("id"));
                    linkTxn.addBatch();
                    counts[1]++;
                }
                linkTxn.executeBatch();
                touchedPostedIds.add(newPostedId);
            }
        }
    }

    private static String txnIdsOf(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows.get(i).get("id"));
        }
        return sb.append(']').toString();
    }

    private static BkProcessAccountingPosted.PeriodType classifyPeriodType(Connection conn, String description,
                                                                            int bankProcessId, LocalDate transactionDate) throws SQLException {
        String d = description == null ? "" : description.toLowerCase();
        if (d.contains("(resend consolidated)")) {
            return BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED;
        }
        if (d.contains("(partial first month)")) {
            return BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH;
        }
        if (d.contains("(day end tail)")) {
            return BkProcessAccountingPosted.PeriodType.DAY_END_TAIL;
        }
        if (d.contains("(once)")) {
            return BkProcessAccountingPosted.PeriodType.ONCE_ONE_OFF;
        }
        if (d.contains("(daily consolidated)")) {
            return BkProcessAccountingPosted.PeriodType.DAILY_CONSOLIDATED;
        }
        if (d.contains("(daily)")) {
            return BkProcessAccountingPosted.PeriodType.DAILY;
        }
        // No suffix in the legacy text -> fall back to the bank_process's own frequency.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT frequency, day_start FROM bank_process WHERE id = ?")) {
            ps.setInt(1, bankProcessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String freq = rs.getString("frequency");
                    if ("MONTHLY".equals(freq)) {
                        return BkProcessAccountingPosted.PeriodType.MONTHLY;
                    }
                    if ("WEEK".equals(freq)) {
                        return BkProcessAccountingPosted.PeriodType.WEEKLY;
                    }
                    if ("DAY".equals(freq)) {
                        return BkProcessAccountingPosted.PeriodType.DAILY;
                    }
                    if ("ONCE".equals(freq)) {
                        return BkProcessAccountingPosted.PeriodType.ONCE_ONE_OFF;
                    }
                    LocalDate dayStart = toLocalDate(rs.getDate("day_start"));
                    if (dayStart != null && YearMonth.from(dayStart).equals(YearMonth.from(transactionDate))
                            && dayStart.getDayOfMonth() == 1) {
                        return BkProcessAccountingPosted.PeriodType.FIRST_MONTH;
                    }
                    return BkProcessAccountingPosted.PeriodType.FULL_MONTH;
                }
            }
        }
        return BkProcessAccountingPosted.PeriodType.MONTHLY;
    }

    private static LocalDate findNextAnchor(Connection conn, int bankProcessId, LocalDate after,
                                             List<LocalDate> siblingStarts) throws SQLException {
        LocalDate dbNext = findNextPostedDate(conn, bankProcessId, after);
        LocalDate memNext = null;
        if (siblingStarts != null) {
            for (LocalDate d : siblingStarts) {
                if (d.isAfter(after) && (memNext == null || d.isBefore(memNext))) {
                    memNext = d;
                }
            }
        }
        if (dbNext == null) {
            return memNext;
        }
        if (memNext == null) {
            return dbNext;
        }
        return dbNext.isBefore(memNext) ? dbNext : memNext;
    }

    private static LocalDate findNextPostedDate(Connection conn, int bankProcessId, LocalDate after) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MIN(posted_date) FROM bank_process_accounting_posted WHERE bank_process_id = ? AND posted_date > ?")) {
            ps.setInt(1, bankProcessId);
            ps.setDate(2, Date.valueOf(after));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Date d = rs.getDate(1);
                    return d != null ? d.toLocalDate() : null;
                }
            }
        }
        return null;
    }

    private static LocalDate parseResendEnd(String description) {
        if (description == null) {
            return null;
        }
        Matcher m = RESEND_END_PATTERN.matcher(description);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }

    // ── Phase 3 (reuses the same regeneration logic as BankProcessDescriptionBackfillTool) ──
    private static void phase3RegenerateDescriptions(Connection conn, BufferedWriter report, boolean apply,
                                                       List<Integer> postedIds, int[] counts) throws SQLException, java.io.IOException {
        if (postedIds.isEmpty()) {
            return;
        }
        Map<Integer, String> bankNameByOptionId = loadBankOptionNames(conn);
        String ids = String.join(",", postedIds.stream().map(String::valueOf).toList());
        String sql = "SELECT t.id, t.account_id, t.amount, t.description AS old_description, "
                + "bpap.period_type, bpap.posted_date, bpap.billing_start, bpap.billing_end, "
                + "bp.id AS bp_id, bp.contract, bp.status, bp.frequency, bp.resend_schedule_frequency, bp.bank_option_id, "
                + "bp.supplier_account_id, bp.supplier_price, bp.customer_account_id, bp.customer_price, "
                + "bp.company_account_id, bp.company_price "
                + "FROM transactions t "
                + "JOIN bank_process_accounting_posted bpap ON bpap.id = t.bank_process_posted_id "
                + "JOIN bank_process bp ON bp.id = bpap.bank_process_id "
                + "WHERE t.bank_process_posted_id IN (" + ids + ")";

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
                    counts[2]++;
                    report.write("SKIP(phase3) txn=" + txnId + " bankProcess=" + bpId + " reason=no_matching_leg_or_share\n");
                    continue;
                }

                String newDescription;
                try {
                    newDescription = BankAccountingDueServiceImpl.buildLineDescription(
                            bp, periodType, postedDate, billingStart, billingEnd,
                            nz(amount), nz(baseAmount), bankName, compensation);
                } catch (RuntimeException ex) {
                    counts[2]++;
                    report.write("SKIP(phase3) txn=" + txnId + " bankProcess=" + bpId + " reason=" + ex + "\n");
                    continue;
                }

                if (newDescription != null && newDescription.equals(oldDescription)) {
                    counts[1]++;
                    continue;
                }
                counts[0]++;
                report.write("CHANGE(phase3) txn=" + txnId + " bankProcess=" + bpId
                        + "\n  old=[" + oldDescription + "]\n  new=[" + newDescription + "]\n");

                if (apply) {
                    updatePs.setString(1, newDescription);
                    updatePs.setInt(2, txnId);
                    updatePs.executeUpdate();
                }
            }
        }
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
