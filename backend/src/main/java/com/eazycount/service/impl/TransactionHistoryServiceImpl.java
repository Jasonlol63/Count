package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionHistoryService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Payment History: Bank Process (Win/Loss) and Domain Payment (Cr/Dr) are built separately,
 * then merged by the public orchestrator so Domain rules do not leak into BP logic.
 */
@Service
public class TransactionHistoryServiceImpl implements TransactionHistoryService {

    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter RANGE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private UserDao userDao;

    @Override
    public TransactionDTO.HistoryResult historyList(TransactionDTO.HistoryRequest request) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (request == null
                || request.getTenantId() == null || request.getTenantId() <= 0
                || request.getAccountId() == null || request.getAccountId() <= 0) {
            throw new BusinessException("Invalid request");
        }

        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }

        Integer tenantId = request.getTenantId();
        Integer accountId = request.getAccountId();
        List<String> currencyCodes = normalizeUpperList(request.getCurrencyCodes());

        UserListDTO account = userDao.findUserByIdAndTenantId(accountId, tenantId);
        if (account == null) {
            throw new BusinessException("Account not found");
        }

        String accountCode = trimToEmpty(account.getAccountId()).toUpperCase(Locale.ROOT);

        HistorySlice bank = buildBankProcessHistorySlice(tenantId, accountId, dateFrom, dateTo, currencyCodes);
        HistorySlice domain = buildDomainPaymentHistorySlice(
                tenantId, accountId, dateFrom, dateTo, currencyCodes, accountCode);

        return mergeHistorySlices(account, dateFrom, dateTo, bank, domain);
    }

    // ── Bank Process (Win/Loss) ───────────────────────────────────────────────
    private HistorySlice buildBankProcessHistorySlice(Integer tenantId, Integer accountId, LocalDate dateFrom, LocalDate dateTo, List<String> currencyCodes) {
        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfRows(bfByCurrency, transactionDao.aggregateBankProcessBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));
        addBfRows(bfByCurrency, transactionDao.aggregateManualAdjustmentBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));

        List<TransactionDTO.HistoryLineRow> lines = new ArrayList<>();
        List<TransactionDTO.HistoryLineRow> bankLines = transactionDao.findBankProcessHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (bankLines != null) {
            lines.addAll(bankLines);
        }
        List<TransactionDTO.HistoryLineRow> adjustmentLines = transactionDao.findManualAdjustmentHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (adjustmentLines != null) {
            lines.addAll(adjustmentLines);
        }
        return new HistorySlice(bfByCurrency, lines);
    }

    // ── Domain Payment (Cr/Dr) ────────────────────────────────────────────────
    private HistorySlice buildDomainPaymentHistorySlice(Integer tenantId, Integer accountId, LocalDate dateFrom, LocalDate dateTo, List<String> currencyCodes, String accountCode) {
        boolean c168ProfitView = "C168".equals(accountCode) || "PROFIT".equals(accountCode);

        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfRows(bfByCurrency, transactionDao.aggregateDomainPaymentBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));

        List<TransactionDTO.HistoryLineRow> lines = new ArrayList<>();
        List<TransactionDTO.HistoryLineRow> domainLines = transactionDao.findDomainPaymentHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (domainLines != null) {
            for (TransactionDTO.HistoryLineRow line : domainLines) {
                if (line == null) {
                    continue;
                }
                if (c168ProfitView) {
                    if (!isNetProfitDescription(line.getDescription())) {
                        continue;
                    }
                    // Retained profit as Cr/Dr (not self-leg net 0).
                    line.setSignedAmount(TransactionMoneyFormat.nz(line.getAmount()));
                }
                applyManualTransferHistoryPresentation(line, accountId);
                lines.add(line);
            }
        }
        return new HistorySlice(bfByCurrency, lines);
    }

    // ── Merge / present ───────────────────────────────────────────────────────
    private TransactionDTO.HistoryResult mergeHistorySlices(
            UserListDTO account,
            LocalDate dateFrom,
            LocalDate dateTo,
            HistorySlice bank,
            HistorySlice domain) {
        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfMap(bfByCurrency, bank.bfByCurrency());
        addBfMap(bfByCurrency, domain.bfByCurrency());

        List<TransactionDTO.HistoryLineRow> lines = new ArrayList<>();
        lines.addAll(bank.lines());
        lines.addAll(domain.lines());
        lines.sort(Comparator
                .comparing(TransactionDTO.HistoryLineRow::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(TransactionDTO.HistoryLineRow::getId, Comparator.nullsLast(Integer::compareTo)));

        Set<String> currencyOrder = new LinkedHashSet<>();
        bfByCurrency.keySet().stream().sorted().forEach(currencyOrder::add);
        for (TransactionDTO.HistoryLineRow line : lines) {
            if (line == null || line.getCurrencyCode() == null) {
                continue;
            }
            currencyOrder.add(line.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }

        Map<String, BigDecimal> balanceByCurrency = new LinkedHashMap<>();
        for (String currency : currencyOrder) {
            balanceByCurrency.put(currency, bfByCurrency.getOrDefault(currency, BigDecimal.ZERO));
        }

        List<TransactionDTO.HistoryRow> history = new ArrayList<>();

        for (String currency : currencyOrder.stream().sorted().toList()) {
            BigDecimal bf = balanceByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            TransactionDTO.HistoryRow bfRow = new TransactionDTO.HistoryRow();
            bfRow.setRowType("bf");
            bfRow.setDate(formatHistoryDate(dateFrom));
            bfRow.setCurrency(currency);
            bfRow.setRate("-");
            bfRow.setWinLoss(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            bfRow.setCrDr(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            bfRow.setBalance(TransactionMoneyFormat.formatMoney(bf));
            bfRow.setDescription("OPENING BALANCE");
            bfRow.setIsBankProcessTransaction(false);
            history.add(bfRow);
        }

        for (TransactionDTO.HistoryLineRow line : lines) {
            if (line == null || line.getId() == null) {
                continue;
            }
            history.add(toHistoryRow(line, balanceByCurrency));
        }

        TransactionDTO.HistoryAccount accountDto = new TransactionDTO.HistoryAccount();
        accountDto.setId(account.getId());
        accountDto.setAccountId(trimToEmpty(account.getAccountId()));
        accountDto.setName(trimToEmpty(account.getName()));

        TransactionDTO.HistoryDateRange range = new TransactionDTO.HistoryDateRange();
        range.setFrom(dateFrom.format(RANGE_DATE));
        range.setTo(dateTo.format(RANGE_DATE));

        TransactionDTO.HistoryResult result = new TransactionDTO.HistoryResult();
        result.setAccount(accountDto);
        result.setDateRange(range);
        result.setHistory(history);
        return result;
    }

    private static TransactionDTO.HistoryRow toHistoryRow(
            TransactionDTO.HistoryLineRow line,
            Map<String, BigDecimal> balanceByCurrency) {
        String currency = line.getCurrencyCode() != null
                ? line.getCurrencyCode().trim().toUpperCase(Locale.ROOT)
                : "";
        BigDecimal signed = line.getSignedAmount() != null
                ? TransactionMoneyFormat.nz(line.getSignedAmount())
                : signedAmountFallback(line.getTransactionType(), line.getAmount());
        BigDecimal running = balanceByCurrency.getOrDefault(currency, BigDecimal.ZERO).add(signed);
        balanceByCurrency.put(currency, running);

        boolean isBank = Boolean.TRUE.equals(line.getBankProcessLine());
        boolean isAdjustment = isManualAdjustmentLine(line);

        TransactionDTO.HistoryRow row = new TransactionDTO.HistoryRow();
        row.setId(line.getId());
        row.setDate(formatHistoryDate(line.getTransactionDate()));
        row.setIsBankProcessTransaction(isBank);
        row.setCardOwner(trimToEmpty(line.getCardOwner()));
        if (isAdjustment) {
            row.setProduct("ADJUSTMENT");
        } else if (!isBank) {
            row.setProduct(resolveDomainHistoryProduct(line));
        }
        row.setCurrency(currency);
        row.setRate("-");
        if (isBank || isAdjustment) {
            row.setWinLoss(TransactionMoneyFormat.formatMoney(signed));
            row.setCrDr(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
        } else {
            row.setWinLoss(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            row.setCrDr(TransactionMoneyFormat.formatMoney(signed));
        }
        row.setBalance(TransactionMoneyFormat.formatMoney(running));
        row.setDescription(trimToEmpty(line.getDescription()));
        row.setRemark(line.getRemark());
        row.setCreatedBy(trimToEmpty(line.getCreatedBy()));
        return row;
    }

    private static void addBfRows(Map<String, BigDecimal> bfByCurrency, List<TransactionDTO.HistoryBfAggregateRow> bfRows) {
        if (bfRows == null) {
            return;
        }
        for (TransactionDTO.HistoryBfAggregateRow bf : bfRows) {
            if (bf == null || bf.getCurrencyCode() == null) {
                continue;
            }
            String code = bf.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
            bfByCurrency.merge(code, TransactionMoneyFormat.nz(bf.getBfAmount()), BigDecimal::add);
        }
    }

    private static void addBfMap(Map<String, BigDecimal> target, Map<String, BigDecimal> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, BigDecimal> e : source.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            target.merge(e.getKey(), TransactionMoneyFormat.nz(e.getValue()), BigDecimal::add);
        }
    }

    static boolean isManualAdjustmentLine(TransactionDTO.HistoryLineRow line) {
        if (line == null || line.getTransactionType() == null) {
            return false;
        }
        return "ADJUSTMENT".equalsIgnoreCase(line.getTransactionType().trim());
    }

    /** Domain History Id Product: PAYMENT / COMMISSION / PROFIT / CLAIM / CLEAR / CONTRA. */
    static String domainProductFromDescription(String description) {
        String d = description != null ? description.trim().toUpperCase(Locale.ROOT) : "";
        if (d.startsWith("PAYMENT FROM ") || d.startsWith("PAYMENT TO ") || d.startsWith("PAY DOMAIN FEE")) {
            return "PAYMENT";
        }
        if (d.startsWith("CLAIM FROM ") || d.startsWith("CLAIM TO ")) {
            return "CLAIM";
        }
        if (d.startsWith("CLEAR FROM ") || d.startsWith("CLEAR TO ")) {
            return "CLEAR";
        }
        if (d.startsWith("CONTRA FROM ") || d.startsWith("CONTRA TO ")) {
            return "CONTRA";
        }
        if (d.contains("COMMISSION")) {
            return "COMMISSION";
        }
        if (d.startsWith("NET PROFIT")) {
            return "PROFIT";
        }
        return "";
    }

    static String resolveDomainHistoryProduct(TransactionDTO.HistoryLineRow line) {
        String fromDescription = domainProductFromDescription(line.getDescription());
        if (!fromDescription.isEmpty()) {
            return fromDescription;
        }
        if (!isManualTransferLine(line.getDescription())) {
            return "";
        }
        String type = line.getTransactionType() != null
                ? line.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : "";
        if ("PAYMENT".equals(type) || "CLAIM".equals(type) || "CLEAR".equals(type) || "CONTRA".equals(type)) {
            return type;
        }
        return "";
    }

    /**
     * Manual PAYMENT / CLAIM / CLEAR / CONTRA rows have no stored description.
     * Receiver (From leg): {TYPE} TO {payer}; payer (To leg): {TYPE} FROM {receiver}.
     */
    static void applyManualTransferHistoryPresentation(
            TransactionDTO.HistoryLineRow line,
            Integer viewedAccountId) {
        if (line == null || viewedAccountId == null || viewedAccountId <= 0) {
            return;
        }
        if (!isManualTransferLine(line.getDescription())) {
            return;
        }
        String type = line.getTransactionType() != null
                ? line.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!"PAYMENT".equals(type) && !"CLAIM".equals(type) && !"CLEAR".equals(type) && !"CONTRA".equals(type)) {
            return;
        }
        String payerCode = trimToEmpty(line.getToAccountCode()).toUpperCase(Locale.ROOT);
        String receiverCode = trimToEmpty(line.getFromAccountCode()).toUpperCase(Locale.ROOT);
        if (line.getFromAccountId() != null && viewedAccountId.equals(line.getFromAccountId())) {
            line.setDescription(type + " TO " + payerCode);
            return;
        }
        if (line.getToAccountId() != null && viewedAccountId.equals(line.getToAccountId())) {
            line.setDescription(type + " FROM " + receiverCode);
        }
    }

    static boolean isManualTransferLine(String description) {
        return description == null || description.isBlank();
    }

    static boolean isNetProfitDescription(String description) {
        String d = description != null ? description.trim().toUpperCase(Locale.ROOT) : "";
        return d.startsWith("NET PROFIT");
    }

    private static BigDecimal signedAmountFallback(String transactionType, BigDecimal amount) {
        BigDecimal value = TransactionMoneyFormat.nz(amount);
        if (transactionType != null && "ADJUSTMENT".equalsIgnoreCase(transactionType.trim())) {
            return value;
        }
        if (transactionType != null && "WIN".equalsIgnoreCase(transactionType.trim())) {
            return value;
        }
        if (transactionType != null && "LOSE".equalsIgnoreCase(transactionType.trim())) {
            return value.negate();
        }
        return value.negate();
    }

    private static String formatHistoryDate(LocalDate date) {
        if (date == null) {
            return "/";
        }
        return date.format(HISTORY_DATE);
    }

    private static List<String> normalizeUpperList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }

    private static String trimToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    /** One source's BF + period lines before merge. */
    private record HistorySlice(
            Map<String, BigDecimal> bfByCurrency,
            List<TransactionDTO.HistoryLineRow> lines) {
    }
}
