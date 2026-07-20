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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransactionHistoryServiceImpl implements TransactionHistoryService {

    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);
    private static final DateTimeFormatter RANGE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private UserDao userDao;

    @Override
    public TransactionDTO.HistoryResult historyBankProcess(TransactionDTO.HistoryRequest request) {
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

        List<TransactionDTO.HistoryBfAggregateRow> bfRows = transactionDao.aggregateBankProcessBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes);
        if (bfRows == null) {
            bfRows = List.of();
        }

        List<TransactionDTO.HistoryLineRow> lines = transactionDao.findBankProcessHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (lines == null) {
            lines = List.of();
        }

        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        for (TransactionDTO.HistoryBfAggregateRow bf : bfRows) {
            if (bf == null || bf.getCurrencyCode() == null) {
                continue;
            }
            String code = bf.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
            bfByCurrency.put(code, TransactionMoneyFormat.nz(bf.getBfAmount()));
        }

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
            String currency = line.getCurrencyCode() != null
                    ? line.getCurrencyCode().trim().toUpperCase(Locale.ROOT)
                    : "";
            BigDecimal signed = signedAmount(line.getTransactionType(), line.getAmount());
            BigDecimal running = balanceByCurrency.getOrDefault(currency, BigDecimal.ZERO).add(signed);
            balanceByCurrency.put(currency, running);

            TransactionDTO.HistoryRow row = new TransactionDTO.HistoryRow();
            row.setId(line.getId());
            row.setDate(formatHistoryDate(line.getTransactionDate()));
            row.setIsBankProcessTransaction(true);
            row.setCardOwner(trimToEmpty(line.getCardOwner()));
            row.setCurrency(currency);
            row.setRate("-");
            row.setWinLoss(TransactionMoneyFormat.formatMoney(signed));
            row.setCrDr(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            row.setBalance(TransactionMoneyFormat.formatMoney(running));
            row.setDescription(trimToEmpty(line.getDescription()));
            row.setRemark(line.getRemark());
            row.setCreatedBy(trimToEmpty(line.getCreatedBy()));
            history.add(row);
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

    private static BigDecimal signedAmount(String transactionType, BigDecimal amount) {
        BigDecimal value = TransactionMoneyFormat.nz(amount);
        if (transactionType != null && "WIN".equalsIgnoreCase(transactionType.trim())) {
            return value;
        }
        return value.negate();
    }

    private static String formatHistoryDate(LocalDate date) {
        if (date == null) {
            return "-";
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
}
