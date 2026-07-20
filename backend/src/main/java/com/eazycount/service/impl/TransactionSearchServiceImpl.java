package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dto.TransactionDTO;
import com.eazycount.entity.Currency;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionSearchService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransactionSearchServiceImpl implements TransactionSearchService {

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public TransactionDTO.SearchResult searchBankProcess(TransactionDTO.SearchRequest request) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }

        List<String> currencyCodes = normalizeUpperList(request.getCurrencyCodes());
        List<String> categories = normalizeUpperList(request.getCategories());

        List<TransactionDTO.SearchAggregateRow> aggregates = transactionDao.aggregateBankProcessWinLoss(
                request.getTenantId(), dateFrom, dateTo, currencyCodes, categories);
        if (aggregates == null) {
            aggregates = List.of();
        }

        List<TransactionDTO.SearchRow> rows = new ArrayList<>();
        BigDecimal totalBf = BigDecimal.ZERO;
        BigDecimal totalWl = BigDecimal.ZERO;

        for (TransactionDTO.SearchAggregateRow agg : aggregates) {
            if (agg == null || agg.getAccountDbId() == null) {
                continue;
            }
            BigDecimal bf = TransactionMoneyFormat.nz(agg.getBfAmount());
            BigDecimal winLoss = TransactionMoneyFormat.nz(agg.getWinLossAmount());
            BigDecimal balance = TransactionMoneyFormat.add(bf, winLoss);
            int periodCount = agg.getPeriodTxnCount() != null ? agg.getPeriodTxnCount() : 0;

            TransactionDTO.SearchRow row = new TransactionDTO.SearchRow();
            row.setAccountId(agg.getAccountDbId());
            row.setAccountCode(trimToEmpty(agg.getAccountCode()));
            row.setAccountName(trimToEmpty(agg.getAccountName()));
            row.setRole(normalizeRole(agg.getRole()));
            row.setCurrencyCode(trimToEmpty(agg.getCurrencyCode()).toUpperCase(Locale.ROOT));
            row.setBf(TransactionMoneyFormat.formatMoney(bf));
            row.setWinLoss(TransactionMoneyFormat.formatMoney(winLoss));
            row.setCrDr("0.00");
            row.setBalance(TransactionMoneyFormat.formatMoney(balance));
            row.setHasWinLossInPeriod(periodCount > 0);
            rows.add(row);

            totalBf = totalBf.add(bf);
            totalWl = totalWl.add(winLoss);
        }

        rows.sort(Comparator
                .comparing(TransactionDTO.SearchRow::getCurrencyCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(TransactionDTO.SearchRow::getAccountCode, Comparator.nullsLast(String::compareTo)));

        TransactionDTO.SearchTotals totals = new TransactionDTO.SearchTotals();
        totals.setBf(TransactionMoneyFormat.formatMoney(totalBf));
        totals.setWinLoss(TransactionMoneyFormat.formatMoney(totalWl));
        totals.setCrDr("0.00");
        totals.setBalance(TransactionMoneyFormat.formatMoney(TransactionMoneyFormat.add(totalBf, totalWl)));

        TransactionDTO.SearchResult result = new TransactionDTO.SearchResult();
        result.setRows(rows);
        result.setTotals(totals);
        result.setActiveCurrencyCodes(resolveActiveCurrencyCodes(request.getTenantId(), rows));
        return result;
    }

    private List<String> resolveActiveCurrencyCodes(Integer tenantId, List<TransactionDTO.SearchRow> rows) {
        Set<String> codes = new LinkedHashSet<>();
        List<Currency> tenantCurrencies = currencyDao.findCurrencyByTenantId(tenantId);
        if (tenantCurrencies != null) {
            for (Currency currency : tenantCurrencies) {
                if (currency != null && currency.getCode() != null && !currency.getCode().isBlank()) {
                    codes.add(currency.getCode().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        for (TransactionDTO.SearchRow row : rows) {
            if (row.getCurrencyCode() != null && !row.getCurrencyCode().isBlank()) {
                codes.add(row.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(codes);
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

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToEmpty(String value) {
        return value != null ? value.trim() : "";
    }
}
