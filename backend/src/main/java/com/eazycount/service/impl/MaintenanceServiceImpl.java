package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankProcessResendDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.TransactionRateDao;
import com.eazycount.dto.TransactionDTO;
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

    private static final Comparator<TransactionDTO.PaymentMaintenanceRow> ROW_ORDER =
            Comparator
                    .comparing(
                            TransactionDTO.PaymentMaintenanceRow::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            TransactionDTO.PaymentMaintenanceRow::getId,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<TransactionDTO.BankProcessMaintenanceRow> BP_ROW_ORDER =
            Comparator
                    .comparing(
                            TransactionDTO.BankProcessMaintenanceRow::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            TransactionDTO.BankProcessMaintenanceRow::getId,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private TransactionRateDao transactionRateDao;

    @Autowired
    private BankProcessResendDao bankProcessResendDao;

    @Override
    public List<TransactionDTO.PaymentMaintenanceRow> findPaymentMaintenanceRows(
            TransactionDTO.PaymentMaintenanceRequest request) {
        requireLoggedIn();
        ListQuery query = parseListQuery(request);

        List<TransactionDTO.PaymentMaintenanceRow> live =
                transactionDao.findPaymentMaintenanceRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.transactionType(),
                        query.currencyCodes(),
                        query.q());
        List<TransactionDTO.PaymentMaintenanceRow> archived =
                transactionDao.findPaymentMaintenanceDeletedRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.transactionType(),
                        query.currencyCodes(),
                        query.q());

        List<TransactionDTO.PaymentMaintenanceRow> rows = new ArrayList<>(live.size() + archived.size());
        rows.addAll(live);
        rows.addAll(archived);
        rows.sort(ROW_ORDER);
        return rows;
    }

    @Override
    public List<TransactionDTO.BankProcessMaintenanceRow> findBankProcessMaintenanceRows(
            TransactionDTO.BankProcessMaintenanceRequest request) {
        requireLoggedIn();
        BankProcessListQuery query = parseBankProcessListQuery(request);

        List<TransactionDTO.BankProcessMaintenanceRow> live =
                transactionDao.findBankProcessMaintenanceRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.currencyCodes(),
                        query.q());
        List<TransactionDTO.BankProcessMaintenanceRow> archived =
                transactionDao.findBankProcessMaintenanceDeletedRows(
                        query.tenantId(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.currencyCodes(),
                        query.q());

        List<TransactionDTO.BankProcessMaintenanceRow> rows =
                new ArrayList<>(live.size() + archived.size());
        rows.addAll(live);
        rows.addAll(archived);
        rows.sort(BP_ROW_ORDER);
        return rows;
    }

    @Override
    @Transactional
    public TransactionDTO.PaymentMaintenanceDeleteResult deletePaymentMaintenanceRows(
            TransactionDTO.PaymentMaintenanceDeleteRequest request) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(request);
        List<Integer> requestedIds = requireTransactionIds(request);

        DeletableBatch batch = resolveDeletableBatch(tenantId, requestedIds);
        if (batch.ids().isEmpty()) {
            throw new BusinessException("No matching payment maintenance records to delete");
        }

        String deletedBy = session.login_id.trim();
        int archived = transactionDao.archivePaymentMaintenanceToDeleted(
                tenantId, batch.ids(), deletedBy);
        if (archived <= 0) {
            throw new BusinessException("Failed to archive payment maintenance records");
        }

        if (!batch.rateGroupIds().isEmpty()) {
            transactionRateDao.deleteByTenantIdAndRateGroupIds(tenantId, batch.rateGroupIds());
        }

        int removed = transactionDao.deleteByIdsAndTenantId(tenantId, batch.ids());
        if (removed <= 0) {
            throw new BusinessException("Failed to delete payment maintenance records");
        }

        TransactionDTO.PaymentMaintenanceDeleteResult result =
                new TransactionDTO.PaymentMaintenanceDeleteResult();
        result.setDeleted(removed);
        return result;
    }

    @Override
    @Transactional
    public TransactionDTO.PaymentMaintenanceDeleteResult deleteBankProcessMaintenanceRows(
            TransactionDTO.BankProcessMaintenanceDeleteRequest request) {
        SessionUser session = requireWritableSession();
        int tenantId = requireTenantId(request);
        List<Integer> requestedIds = requireTransactionIds(request);

        BankProcessDeletableBatch batch = resolveBankProcessDeletableBatch(tenantId, requestedIds);
        if (batch.ids().isEmpty()) {
            throw new BusinessException("No matching bank process maintenance records to delete");
        }

        String deletedBy = session.login_id.trim();
        int archived = transactionDao.archiveBankProcessMaintenanceToDeleted(
                tenantId, batch.ids(), deletedBy);
        if (archived <= 0) {
            throw new BusinessException("Failed to archive bank process maintenance records");
        }

        if (!batch.bankProcessIds().isEmpty()) {
            bankProcessResendDao.deleteDailyGuardByTenantAndBankProcessIds(
                    tenantId, batch.bankProcessIds());
        }

        int removed = transactionDao.deleteByIdsAndTenantId(tenantId, batch.ids());
        if (removed <= 0) {
            throw new BusinessException("Failed to delete bank process maintenance records");
        }

        TransactionDTO.PaymentMaintenanceDeleteResult result =
                new TransactionDTO.PaymentMaintenanceDeleteResult();
        result.setDeleted(removed);
        return result;
    }

    private BankProcessDeletableBatch resolveBankProcessDeletableBatch(
            int tenantId, List<Integer> requestedIds) {
        List<Transaction> selected = transactionDao.findByIdsAndTenantId(tenantId, requestedIds);
        List<Integer> ids = filterBankProcessDeletableIds(selected);
        if (ids.isEmpty()) {
            return new BankProcessDeletableBatch(List.of(), List.of());
        }

        List<Integer> postedIds = postedIdsFrom(selected, ids);
        if (!postedIds.isEmpty()) {
            Set<Integer> expanded = new LinkedHashSet<>(ids);
            expanded.addAll(
                    transactionDao.findBankProcessMaintenanceIdsByPostedIds(tenantId, postedIds));
            ids = new ArrayList<>(expanded);
        }

        List<Integer> bankProcessIds =
                transactionDao.findBankProcessIdsByTransactionIds(tenantId, ids);
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
        List<Transaction> selected = transactionDao.findByIdsAndTenantId(tenantId, requestedIds);
        List<Integer> ids = filterDeletableIds(selected);
        if (ids.isEmpty()) {
            return new DeletableBatch(List.of(), List.of());
        }

        List<String> rateGroupIds = rateGroupIdsFrom(selected, ids);
        if (!rateGroupIds.isEmpty()) {
            Set<Integer> expanded = new LinkedHashSet<>(ids);
            expanded.addAll(transactionDao.findPaymentMaintenanceIdsByRateGroupIds(tenantId, rateGroupIds));
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

    private static ListQuery parseListQuery(TransactionDTO.PaymentMaintenanceRequest request) {
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
            TransactionDTO.BankProcessMaintenanceRequest request) {
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

    private static int requireTenantId(TransactionDTO.PaymentMaintenanceDeleteRequest request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return request.getTenantId();
    }

    private static int requireTenantId(TransactionDTO.BankProcessMaintenanceDeleteRequest request) {
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return request.getTenantId();
    }

    private static List<Integer> requireTransactionIds(
            TransactionDTO.PaymentMaintenanceDeleteRequest request) {
        List<Integer> ids = normalizeIds(request != null ? request.getTransactionIds() : null);
        if (ids.isEmpty()) {
            throw new BusinessException("Please select at least one record");
        }
        return ids;
    }

    private static List<Integer> requireTransactionIds(
            TransactionDTO.BankProcessMaintenanceDeleteRequest request) {
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
