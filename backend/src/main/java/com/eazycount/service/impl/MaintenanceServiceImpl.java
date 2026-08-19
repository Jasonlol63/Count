package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankProcessResendDao;
import com.eazycount.dao.MaintenanceDao;
import com.eazycount.dao.TransactionRateDao;
import com.eazycount.dto.MaintenanceBankProcessDTO;
import com.eazycount.dto.MaintenanceCaptureDTO;
import com.eazycount.dto.MaintenanceFormulaDTO;
import com.eazycount.dto.MaintenancePaymentDTO;
import com.eazycount.dto.MaintenanceTransactionDTO;
import com.eazycount.entity.Transaction;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.MaintenanceService;
import com.eazycount.util.TransactionDateParse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MaintenanceServiceImpl implements MaintenanceService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "PAYMENT", "CLAIM", "CLEAR", "CONTRA", "RATE", "ADJUSTMENT", "PROFIT");

    private static final Set<String> BANK_PROCESS_MAINTENANCE_TYPES = Set.of("WIN", "LOSE");

    private static final Comparator<MaintenancePaymentDTO> ROW_ORDER =
            Comparator
                    .comparing(
                            MaintenancePaymentDTO::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            MaintenancePaymentDTO::getId,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<MaintenanceBankProcessDTO> BP_ROW_ORDER =
            Comparator
                    .comparing(
                            MaintenanceBankProcessDTO::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            MaintenanceBankProcessDTO::getId,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<MaintenanceTransactionDTO> TC_ROW_ORDER =
            Comparator
                    .comparing(MaintenanceTransactionDTO::getDtsCreated,
                               Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(MaintenanceTransactionDTO::getId,
                                   Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<MaintenanceCaptureDTO> CC_ROW_ORDER =
            Comparator
                    .comparing(MaintenanceCaptureDTO::getDtsCreated,
                               Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(MaintenanceCaptureDTO::getId,
                                   Comparator.nullsLast(Comparator.reverseOrder()));

    @Autowired
    private MaintenanceDao maintenanceDao;

    @Autowired
    private TransactionRateDao transactionRateDao;

    @Autowired
    private BankProcessResendDao bankProcessResendDao;

    @Override
    public List<MaintenanceTransactionDTO> findMaintenanceTransactionsRows(MaintenanceTransactionDTO mt) {
        requireLoggedIn();
        ProcessCategoryListQuery query = parseTransactionListQuery(mt);

        List<MaintenanceTransactionDTO> rows = maintenanceDao.findTransactionLineMaintenanceRows(
                query.tenantId(),
                query.dateFrom(),
                query.dateTo(),
                query.process(),
                query.category(),
                query.q());
        rows.sort(TC_ROW_ORDER);
        return rows;
    }

    @Override
    public List<MaintenanceCaptureDTO> findMaintenanceCaptureRows(MaintenanceCaptureDTO mc) {
        requireLoggedIn();
        ProcessCategoryListQuery query = parseCaptureListQuery(mc);

        List<MaintenanceCaptureDTO> live = maintenanceDao.findCaptureLineMaintenanceRows(
                query.tenantId(),
                query.dateFrom(),
                query.dateTo(),
                query.process(),
                query.category(),
                query.q());
        List<MaintenanceCaptureDTO> archived = maintenanceDao.findCaptureLineMaintenanceDeletedRows(
                query.tenantId(),
                query.dateFrom(),
                query.dateTo(),
                query.process(),
                query.category(),
                query.q());

        List<MaintenanceCaptureDTO> rows = new ArrayList<>(live.size() + archived.size());
        rows.addAll(live);
        rows.addAll(archived);
        rows.sort(CC_ROW_ORDER);
        return rows;
    }

    // Capture Maintenance delete: unit is always the whole capture (data_captures.id) — the list is already
    // one row per capture, so `mc.captureIds` are exactly the ids to act on, no line-id resolution needed.
    @Override
    @Transactional
    public void deleteMaintenanceCaptureRows(MaintenanceCaptureDTO mc) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(mc != null ? mc.getTenantId() : null);
        List<Integer> captureIds = requireIds(mc != null ? mc.getCaptureIds() : null);

        String deletedBy = session.login_id.trim();

        List<Integer> transactionIds =
                maintenanceDao.findCaptureLineTransactionIdsByCaptureIdsAndTenantId(tenantId, captureIds);
        if (!transactionIds.isEmpty()) {
            int archivedTransactions =
                    maintenanceDao.archiveCaptureTransactionsToDeleted(tenantId, transactionIds, deletedBy);
            if (archivedTransactions <= 0) {
                throw new BusinessException("Failed to archive linked transactions");
            }
            maintenanceDao.deleteByIdsAndTenantId(tenantId, transactionIds);
        }

        int archivedLines = maintenanceDao.archiveCaptureLineMaintenanceToDeleted(tenantId, captureIds, deletedBy);
        if (archivedLines <= 0) {
            throw new BusinessException("Failed to archive capture maintenance records");
        }

        int removed = maintenanceDao.deleteCaptureLineMaintenanceByCaptureIds(tenantId, captureIds);
        if (removed <= 0) {
            throw new BusinessException("Failed to delete capture maintenance records");
        }

        maintenanceDao.deleteProcessSubmittedByCaptureIds(tenantId, captureIds);
    }

    @Override
    public List<MaintenanceFormulaDTO> findMaintenanceFormulaRows(MaintenanceFormulaDTO mf) {
        requireLoggedIn();
        FormulaListQuery query = parseFormulaListQuery(mf);

        return maintenanceDao.findFormulaMaintenanceRows(
                query.tenantId(),
                query.process(),
                query.category(),
                query.q());
    }

    // Formula Maintenance Edit: only account_id/source_percent/input_method/formula/description are editable.
    // enable_source_percent/enable_input_method are left untouched; updated_at auto-refreshes (ON UPDATE CURRENT_TIMESTAMP).
    @Override
    @Transactional
    public void updateFormulaMaintenance(MaintenanceFormulaDTO ft) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(ft != null ? ft.getTenantId() : null);
        int id = requireFormulaId(ft);

        int updated = maintenanceDao.updateFormulaMaintenanceRow(
                tenantId,
                id,
                ft.getAccountId(),
                normalizeSourcePercent(ft.getSourcePercent()),
                normalizeQ(ft.getInputMethod()),
                normalizeQ(ft.getFormula()),
                normalizeQ(ft.getDescription()),
                session.login_id.trim());
        if (updated <= 0) {
            throw new BusinessException("Formula maintenance record not found");
        }
    }

    // Formula Maintenance Delete: hard delete, batch by id, tenant-scoped — no archive/soft-delete.
    @Override
    @Transactional
    public void deleteFormulaMaintenance(MaintenanceFormulaDTO ft) {
        requireWritableSession();
        int tenantId = requireTenantId(ft != null ? ft.getTenantId() : null);
        List<Integer> ids = requireIds(ft != null ? ft.getFormulaIds() : null);

        int removed = maintenanceDao.deleteFormulaMaintenanceRows(tenantId, ids);
        if (removed <= 0) {
            throw new BusinessException("No matching formula maintenance records to delete");
        }
    }

    @Override
    public List<MaintenancePaymentDTO> findPaymentMaintenanceRows(
            MaintenancePaymentDTO request) {
        requireLoggedIn();
        ListQuery query = parseListQuery(request);

        List<MaintenancePaymentDTO> live =
                maintenanceDao.findPaymentMaintenanceRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.transactionType(),
                        query.currencyCodes(),
                        query.q());
        List<MaintenancePaymentDTO> archived =
                maintenanceDao.findPaymentMaintenanceDeletedRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.transactionType(),
                        query.currencyCodes(),
                        query.q());

        List<MaintenancePaymentDTO> rows = new ArrayList<>(live.size() + archived.size());
        rows.addAll(live);
        rows.addAll(archived);
        rows.sort(ROW_ORDER);
        return rows;
    }

    @Override
    public List<MaintenanceBankProcessDTO> findBankProcessMaintenanceRows(
            MaintenanceBankProcessDTO request) {
        requireLoggedIn();
        ListQuery query = parseBankProcessListQuery(request);

        List<MaintenanceBankProcessDTO> live =
                maintenanceDao.findBankProcessMaintenanceRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.currencyCodes(),
                        query.q());
        List<MaintenanceBankProcessDTO> archived =
                maintenanceDao.findBankProcessMaintenanceDeletedRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.currencyCodes(),
                        query.q());

        List<MaintenanceBankProcessDTO> rows =
                new ArrayList<>(live.size() + archived.size());
        rows.addAll(live);
        rows.addAll(archived);
        rows.sort(BP_ROW_ORDER);
        return rows;
    }

    @Override
    @Transactional
    public void deletePaymentMaintenanceRows(
            MaintenancePaymentDTO request) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(request != null ? request.getTenantId() : null);
        List<Integer> requestedIds = requireIds(request != null ? request.getTransactionIds() : null);

        DeletableBatch batch = resolveDeletableBatch(tenantId, requestedIds);
        if (batch.ids().isEmpty()) {
            throw new BusinessException("No matching payment maintenance records to delete");
        }

        String deletedBy = session.login_id.trim();
        int archived = maintenanceDao.archivePaymentMaintenanceToDeleted(
                tenantId, batch.ids(), deletedBy);
        if (archived <= 0) {
            throw new BusinessException("Failed to archive payment maintenance records");
        }

        if (!batch.rateGroupIds().isEmpty()) {
            transactionRateDao.deleteByTenantIdAndRateGroupIds(tenantId, batch.rateGroupIds());
        }

        int removed = maintenanceDao.deleteByIdsAndTenantId(tenantId, batch.ids());
        if (removed <= 0) {
            throw new BusinessException("Failed to delete payment maintenance records");
        }
    }

    @Override
    @Transactional
    public void deleteBankProcessMaintenanceRows(
            MaintenanceBankProcessDTO request) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(request != null ? request.getTenantId() : null);
        List<Integer> requestedIds = requireIds(request != null ? request.getTransactionIds() : null);

        BankProcessDeletableBatch batch = resolveBankProcessDeletableBatch(tenantId, requestedIds);
        if (batch.ids().isEmpty()) {
            throw new BusinessException("No matching bank process maintenance records to delete");
        }

        String deletedBy = session.login_id.trim();
        int archived = maintenanceDao.archiveBankProcessMaintenanceToDeleted(
                tenantId, batch.ids(), deletedBy);
        if (archived <= 0) {
            throw new BusinessException("Failed to archive bank process maintenance records");
        }

        if (!batch.bankProcessIds().isEmpty()) {
            bankProcessResendDao.deleteDailyGuardByTenantAndBankProcessIds(
                    tenantId, batch.bankProcessIds());
        }

        int removed = maintenanceDao.deleteByIdsAndTenantId(tenantId, batch.ids());
        if (removed <= 0) {
            throw new BusinessException("Failed to delete bank process maintenance records");
        }
    }

    private BankProcessDeletableBatch resolveBankProcessDeletableBatch(
            int tenantId, List<Integer> requestedIds) {
        List<Transaction> selected = maintenanceDao.findByIdsAndTenantId(tenantId, requestedIds);
        List<Integer> ids = filterBankProcessDeletableIds(selected);
        if (ids.isEmpty()) {
            return new BankProcessDeletableBatch(List.of(), List.of());
        }

        List<Integer> postedIds = postedIdsFrom(selected, ids);
        if (!postedIds.isEmpty()) {
            Set<Integer> expanded = new LinkedHashSet<>(ids);
            expanded.addAll(
                    maintenanceDao.findBankProcessMaintenanceIdsByPostedIds(tenantId, postedIds));
            ids = new ArrayList<>(expanded);
        }

        List<Integer> bankProcessIds =
                maintenanceDao.findBankProcessIdsByTransactionIds(tenantId, ids);
        return new BankProcessDeletableBatch(ids, bankProcessIds);
    }

    private static List<Integer> filterBankProcessDeletableIds(List<Transaction> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Transaction row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0) {
                continue;
            }
            if (row.getBankProcessPostedId() == null) {
                continue;
            }
            Transaction.TransactionType type = row.getTransactionType();
            if (type == null || !BANK_PROCESS_MAINTENANCE_TYPES.contains(type.name())) {
                continue;
            }
            if (row.getApprovalStatus() != Transaction.ApprovalStatus.APPROVED) {
                continue;
            }
            ids.add(row.getId());
        }
        return ids;
    }

    private static List<Integer> postedIdsFrom(List<Transaction> rows, List<Integer> ids) {
        if (rows == null || rows.isEmpty() || ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Integer> idSet = new LinkedHashSet<>(ids);
        Set<Integer> postedIds = new LinkedHashSet<>();
        for (Transaction row : rows) {
            if (row == null || row.getId() == null || !idSet.contains(row.getId())) {
                continue;
            }
            Integer postedId = row.getBankProcessPostedId();
            if (postedId != null && postedId > 0) {
                postedIds.add(postedId);
            }
        }
        return new ArrayList<>(postedIds);
    }

    private DeletableBatch resolveDeletableBatch(int tenantId, List<Integer> requestedIds) {
        List<Transaction> selected = maintenanceDao.findByIdsAndTenantId(tenantId, requestedIds);
        List<Integer> ids = filterDeletableIds(selected);
        if (ids.isEmpty()) {
            return new DeletableBatch(List.of(), List.of());
        }

        List<String> rateGroupIds = rateGroupIdsFrom(selected, ids);
        if (!rateGroupIds.isEmpty()) {
            Set<Integer> expanded = new LinkedHashSet<>(ids);
            expanded.addAll(maintenanceDao.findPaymentMaintenanceIdsByRateGroupIds(tenantId, rateGroupIds));
            ids = new ArrayList<>(expanded);
        }

        return new DeletableBatch(ids, rateGroupIds);
    }

    private static List<Integer> filterDeletableIds(List<Transaction> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Transaction row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0) {
                continue;
            }
            if (row.getBankProcessPostedId() != null) {
                continue;
            }
            Transaction.TransactionType type = row.getTransactionType();
            if (type == null || !ALLOWED_TYPES.contains(type.name())) {
                continue;
            }
            ids.add(row.getId());
        }
        return ids;
    }

    private static List<String> rateGroupIdsFrom(List<Transaction> rows, List<Integer> ids) {
        if (rows == null || rows.isEmpty() || ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Integer> idSet = new LinkedHashSet<>(ids);
        Set<String> rateGroupIds = new LinkedHashSet<>();
        for (Transaction row : rows) {
            if (row == null || row.getId() == null || !idSet.contains(row.getId())) {
                continue;
            }
            String rateGroupId = trimToNull(row.getRateGroupId());
            if (rateGroupId != null) {
                rateGroupIds.add(rateGroupId);
            }
        }
        return new ArrayList<>(rateGroupIds);
    }

    private static DateRangeTenantQuery parseDateRangeTenantQuery(
            Integer tenantId, String dateFromRaw, String dateToRaw) {
        int validTenantId = requireTenantId(tenantId);
        LocalDate dateFrom = TransactionDateParse.parseRequired(dateFromRaw, "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(dateToRaw, "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }
        return new DateRangeTenantQuery(validTenantId, dateFrom, dateTo);
    }

    private static ListQuery parseListQuery(MaintenancePaymentDTO request) {
        DateRangeTenantQuery base = parseDateRangeTenantQuery(
                request != null ? request.getTenantId() : null,
                request != null ? request.getDateFrom() : null,
                request != null ? request.getDateTo() : null);
        return new ListQuery(
                base.tenantId(),
                base.dateFrom(),
                base.dateTo(),
                normalizeType(request.getTransactionType()),
                normalizeUpperList(request.getCurrencyCodes()),
                normalizeQ(request.getQ()));
    }

    private static ListQuery parseBankProcessListQuery(
            MaintenanceBankProcessDTO request) {
        DateRangeTenantQuery base = parseDateRangeTenantQuery(
                request != null ? request.getTenantId() : null,
                request != null ? request.getDateFrom() : null,
                request != null ? request.getDateTo() : null);
        return new ListQuery(
                base.tenantId(),
                base.dateFrom(),
                base.dateTo(),
                null,
                normalizeUpperList(request.getCurrencyCodes()),
                normalizeQ(request.getQ()));
    }

    private static ProcessCategoryListQuery parseTransactionListQuery(MaintenanceTransactionDTO request) {
        DateRangeTenantQuery base = parseDateRangeTenantQuery(
                request != null ? request.getTenantId() : null,
                request != null ? request.getDateFrom() : null,
                request != null ? request.getDateTo() : null);
        return new ProcessCategoryListQuery(
                base.tenantId(),
                base.dateFrom(),
                base.dateTo(),
                normalizeQ(request.getProcess()),
                normalizeMaintenanceCategory(request.getCategory()),
                normalizeQ(request.getQ()));
    }

    private static ProcessCategoryListQuery parseCaptureListQuery(MaintenanceCaptureDTO request) {
        DateRangeTenantQuery base = parseDateRangeTenantQuery(
                request != null ? request.getTenantId() : null,
                request != null ? request.getDateFrom() : null,
                request != null ? request.getDateTo() : null);
        return new ProcessCategoryListQuery(
                base.tenantId(),
                base.dateFrom(),
                base.dateTo(),
                normalizeQ(request.getProcess()),
                normalizeMaintenanceCategory(request.getCategory()),
                normalizeQ(request.getQ()));
    }

    private static FormulaListQuery parseFormulaListQuery(MaintenanceFormulaDTO request) {
        int tenantId = requireTenantId(request != null ? request.getTenantId() : null);
        return new FormulaListQuery(
                tenantId,
                normalizeQ(request.getProcess()),
                normalizeMaintenanceCategory(request.getCategory()),
                normalizeQ(request.getQ()));
    }

    private static String normalizeMaintenanceCategory(String raw) {
        String category = trimToNull(raw);
        if (category == null) {
            throw new BusinessException("category is required");
        }
        String lower = category.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "games" -> "GAME";
            case "bank"  -> "BANK";
            default -> throw new BusinessException("Unsupported category: " + category);
        };
    }

    private static void requireLoggedIn() {
        if (SecurityUtils.currentUser() == null) {
            throw new BusinessException("Not logged in");
        }
    }

    private static SessionUser requireWritableSession() {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (session.read_only == 1) {
            throw new BusinessException("Read-only access cannot delete transactions");
        }
        if (session.login_id == null || session.login_id.isBlank()) {
            throw new BusinessException("Invalid session login id");
        }
        return session;
    }

    private static int requireTenantId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return tenantId;
    }

    private static List<Integer> requireIds(List<Integer> raw) {
        List<Integer> ids = normalizeIds(raw);
        if (ids.isEmpty()) {
            throw new BusinessException("Please select at least one record");
        }
        return ids;
    }

    private static int requireFormulaId(MaintenanceFormulaDTO request) {
        if (request == null || request.getId() == null || request.getId() <= 0) {
            throw new BusinessException("Invalid formula id");
        }
        return request.getId();
    }

    // data_capture_formula.source_percent is NOT NULL DEFAULT '0'; a blank edit falls back to that default.
    private static String normalizeSourcePercent(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed != null ? trimmed : "0";
    }

    private record DateRangeTenantQuery(Integer tenantId, LocalDate dateFrom, LocalDate dateTo) {}

    private record ListQuery(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            String transactionType,
            List<String> currencyCodes,
            String q) {}

    private record ProcessCategoryListQuery(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            String process,
            String category,
            String q) {}

    private record FormulaListQuery(
            Integer tenantId,
            String process,
            String category,
            String q) {}

    private record DeletableBatch(List<Integer> ids, List<String> rateGroupIds) {}

    private record BankProcessDeletableBatch(List<Integer> ids, List<Integer> bankProcessIds) {}

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<Integer> normalizeIds(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (Integer id : raw) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) {
            return null;
        }
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BusinessException("Unsupported transaction type: " + type);
        }
        return type;
    }

    private static String normalizeQ(String raw) {
        if (raw == null) {
            return null;
        }
        String q = raw.trim();
        return q.isEmpty() ? null : q;
    }

    private static List<String> normalizeUpperList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String v = item.trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
