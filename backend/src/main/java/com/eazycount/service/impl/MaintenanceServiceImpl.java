package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankProcessResendDao;
import com.eazycount.dao.MaintenanceDao;
import com.eazycount.dao.TransactionRateDao;
import com.eazycount.dto.MaintenanceBankProcessDTO;
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

    @Autowired
    private MaintenanceDao maintenanceDao;

    @Autowired
    private TransactionRateDao transactionRateDao;

    @Autowired
    private BankProcessResendDao bankProcessResendDao;

    @Override
    public List<MaintenanceTransactionDTO> findMaintenanceTransactionsRows(MaintenanceTransactionDTO mt) {
        requireLoggedIn();
        TransactionListQuery query = parseTransactionListQuery(mt);

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
        BankProcessListQuery query = parseBankProcessListQuery(request);

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
        int tenantId = requireTenantId(request);
        List<Integer> requestedIds = requireTransactionIds(request);

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
        int tenantId = requireTenantId(request);
        List<Integer> requestedIds = requireTransactionIds(request);

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

    private static ListQuery parseListQuery(MaintenancePaymentDTO request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }
        return new ListQuery(
                request.getTenantId(),
                dateFrom,
                dateTo,
                normalizeType(request.getTransactionType()),
                normalizeUpperList(request.getCurrencyCodes()),
                normalizeQ(request.getQ()));
    }

    private static BankProcessListQuery parseBankProcessListQuery(
            MaintenanceBankProcessDTO request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }
        return new BankProcessListQuery(
                request.getTenantId(),
                dateFrom,
                dateTo,
                normalizeUpperList(request.getCurrencyCodes()),
                normalizeQ(request.getQ()));
    }

    private static TransactionListQuery parseTransactionListQuery(MaintenanceTransactionDTO request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }
        return new TransactionListQuery(
                request.getTenantId(),
                dateFrom,
                dateTo,
                normalizeQ(request.getProcess()),
                normalizeTransactionCategory(request.getCategory()),
                normalizeQ(request.getQ()));
    }

    // Games/Gambling/Loan/Rate/Money share the GAME data_captures.category; Bank maps to BANK.
    // Required (never defaulted): a missing/unrecognized category would let GAME and BANK rows mix in one response.
    private static String normalizeTransactionCategory(String raw) {
        String category = trimToNull(raw);
        if (category == null) {
            throw new BusinessException("category is required");
        }
        String lower = category.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "games", "gambling", "loan", "rate", "money" -> "GAME";
            case "bank" -> "BANK";
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

    private static int requireTenantId(MaintenancePaymentDTO request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return request.getTenantId();
    }

    private static int requireTenantId(MaintenanceBankProcessDTO request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return request.getTenantId();
    }

    private static List<Integer> requireTransactionIds(
            MaintenancePaymentDTO request) {
        List<Integer> ids = normalizeIds(request != null ? request.getTransactionIds() : null);
        if (ids.isEmpty()) {
            throw new BusinessException("Please select at least one record");
        }
        return ids;
    }

    private static List<Integer> requireTransactionIds(
            MaintenanceBankProcessDTO request) {
        List<Integer> ids = normalizeIds(request != null ? request.getTransactionIds() : null);
        if (ids.isEmpty()) {
            throw new BusinessException("Please select at least one record");
        }
        return ids;
    }

    private record ListQuery(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            String transactionType,
            List<String> currencyCodes,
            String q) {}

    private record BankProcessListQuery(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<String> currencyCodes,
            String q) {}

    private record TransactionListQuery(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
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
