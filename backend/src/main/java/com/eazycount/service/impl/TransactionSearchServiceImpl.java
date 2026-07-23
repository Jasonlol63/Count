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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Transaction search: Bank Process (Win/Loss) and Domain Payment (Cr/Dr) are built separately,
 * then merged by {@link #searchList} so Domain rules do not leak into BP logic.
 */
@Service
public class TransactionSearchServiceImpl implements TransactionSearchService {

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public TransactionDTO.SearchResult searchList(TransactionDTO.SearchRequest request) {
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
        Integer tenantId = request.getTenantId();

        SearchSlice bank = buildBankProcessSearchSlice(tenantId, dateFrom, dateTo, currencyCodes, categories);
        SearchSlice domain = buildDomainPaymentSearchSlice(tenantId, dateFrom, dateTo, currencyCodes, categories);

        return mergeSearchSlices(tenantId, bank, domain);
    }

    // ── Bank Process (Win/Loss) ───────────────────────────────────────────────
    private SearchSlice buildBankProcessSearchSlice(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<String> currencyCodes,
            List<String> categories) {
        List<TransactionDTO.SearchAggregateRow> bankRows = transactionDao.aggregateBankProcessWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionDTO.SearchAggregateRow> adjustmentRows = transactionDao.aggregateManualAdjustmentWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionDTO.SearchAggregateRow> profitRows = transactionDao.aggregateManualProfitWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionDTO.SearchAggregateRow> rateMiddlemanRows = transactionDao.aggregateManualRateMiddlemanWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        if (bankRows == null) {
            bankRows = List.of();
        }
        if (adjustmentRows == null) {
            adjustmentRows = List.of();
        }
        if (profitRows == null) {
            profitRows = List.of();
        }
        if (rateMiddlemanRows == null) {
            rateMiddlemanRows = List.of();
        }
        List<TransactionDTO.SearchAggregateRow> combined = mergeWinLossAggregateRows(bankRows, adjustmentRows);
        combined = mergeWinLossAggregateRows(combined, profitRows);
        combined = mergeWinLossAggregateRows(combined, rateMiddlemanRows);
        return new SearchSlice(combined, true);
    }

    private static List<TransactionDTO.SearchAggregateRow> mergeWinLossAggregateRows(List<TransactionDTO.SearchAggregateRow> first, List<TransactionDTO.SearchAggregateRow> second) {
        Map<String, TransactionDTO.SearchAggregateRow> merged = new HashMap<>();
        for (TransactionDTO.SearchAggregateRow row : first) {
            absorbWinLossAggregate(merged, row);
        }
        for (TransactionDTO.SearchAggregateRow row : second) {
            absorbWinLossAggregate(merged, row);
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparing(TransactionDTO.SearchAggregateRow::getCurrencyCode,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(TransactionDTO.SearchAggregateRow::getAccountCode,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static void absorbWinLossAggregate(Map<String, TransactionDTO.SearchAggregateRow> merged, TransactionDTO.SearchAggregateRow row) {
        if (row == null || row.getAccountDbId() == null) {
            return;
        }
        String key = row.getAccountDbId() + "|"
                + trimToEmpty(row.getCurrencyCode()).toUpperCase(Locale.ROOT);
        TransactionDTO.SearchAggregateRow existing = merged.get(key);
        if (existing == null) {
            TransactionDTO.SearchAggregateRow copy = new TransactionDTO.SearchAggregateRow();
            copy.setAccountDbId(row.getAccountDbId());
            copy.setAccountCode(row.getAccountCode());
            copy.setAccountName(row.getAccountName());
            copy.setRole(row.getRole());
            copy.setCurrencyCode(row.getCurrencyCode());
            copy.setBfAmount(TransactionMoneyFormat.nz(row.getBfAmount()));
            copy.setWinLossAmount(TransactionMoneyFormat.nz(row.getWinLossAmount()));
            copy.setCrDrAmount(TransactionMoneyFormat.nz(row.getCrDrAmount()));
            copy.setPeriodTxnCount(row.getPeriodTxnCount() != null ? row.getPeriodTxnCount() : 0);
            merged.put(key, copy);
            return;
        }
        existing.setBfAmount(TransactionMoneyFormat.nz(existing.getBfAmount())
                .add(TransactionMoneyFormat.nz(row.getBfAmount())));
        existing.setWinLossAmount(TransactionMoneyFormat.nz(existing.getWinLossAmount())
                .add(TransactionMoneyFormat.nz(row.getWinLossAmount())));
        existing.setPeriodTxnCount(
                (existing.getPeriodTxnCount() != null ? existing.getPeriodTxnCount() : 0)
                        + (row.getPeriodTxnCount() != null ? row.getPeriodTxnCount() : 0));
    }

    // ── Domain Payment (Cr/Dr) ────────────────────────────────────────────────
    private SearchSlice buildDomainPaymentSearchSlice(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, List<String> currencyCodes, List<String> categories) {
        List<TransactionDTO.SearchAggregateRow> domainRows = transactionDao.aggregateDomainPaymentCrDr(tenantId, dateFrom, dateTo, currencyCodes, categories);
        if (domainRows == null) {
            domainRows = List.of();
        }
        return new SearchSlice(domainRows, false);
    }

    // ── Merge / present ───────────────────────────────────────────────────────
    private TransactionDTO.SearchResult mergeSearchSlices(Integer tenantId, SearchSlice bank, SearchSlice domain) {
        Map<String, MergedAccount> merged = new HashMap<>();
        applyBankAggregates(merged, bank.aggregates());
        applyDomainAggregates(merged, domain.aggregates());

        List<TransactionDTO.SearchRow> rows = new ArrayList<>();
        BigDecimal totalBf = BigDecimal.ZERO;
        BigDecimal totalWl = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;

        for (MergedAccount agg : merged.values()) {
            // Domain-only all-zero with no period activity (e.g. only historical NET PROFIT) → hide
            if (!agg.fromBank
                    && agg.periodCrDrCount <= 0
                    && agg.bf.compareTo(BigDecimal.ZERO) == 0
                    && agg.crDr.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal balance = TransactionMoneyFormat.add(TransactionMoneyFormat.add(agg.bf, agg.winLoss), agg.crDr);

            TransactionDTO.SearchRow row = new TransactionDTO.SearchRow();
            row.setAccountId(agg.accountDbId);
            row.setAccountCode(trimToEmpty(agg.accountCode));
            row.setAccountName(trimToEmpty(agg.accountName));
            row.setRole(normalizeRole(agg.role));
            row.setCurrencyCode(trimToEmpty(agg.currencyCode).toUpperCase(Locale.ROOT));
            row.setBf(TransactionMoneyFormat.formatMoney(agg.bf));
            row.setWinLoss(TransactionMoneyFormat.formatMoney(agg.winLoss));
            row.setCrDr(TransactionMoneyFormat.formatMoney(agg.crDr));
            row.setBalance(TransactionMoneyFormat.formatMoney(balance));
            row.setHasWinLossInPeriod(agg.periodWinLossCount > 0);
            // Includes NET PROFIT self-leg (period count > 0 even when Cr/Dr nets to 0.00)
            row.setHasCrDrInPeriod(agg.periodCrDrCount > 0);
            rows.add(row);

            totalBf = totalBf.add(agg.bf);
            totalWl = totalWl.add(agg.winLoss);
            totalCr = totalCr.add(agg.crDr);
        }

        rows.sort(Comparator
                .comparing(TransactionDTO.SearchRow::getCurrencyCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(TransactionDTO.SearchRow::getAccountCode, Comparator.nullsLast(String::compareTo)));

        TransactionDTO.SearchTotals totals = new TransactionDTO.SearchTotals();
        totals.setBf(TransactionMoneyFormat.formatMoney(totalBf));
        totals.setWinLoss(TransactionMoneyFormat.formatMoney(totalWl));
        totals.setCrDr(TransactionMoneyFormat.formatMoney(totalCr));
        totals.setBalance(TransactionMoneyFormat.formatMoney(
                TransactionMoneyFormat.add(TransactionMoneyFormat.add(totalBf, totalWl), totalCr)));

        TransactionDTO.SearchResult result = new TransactionDTO.SearchResult();
        result.setRows(rows);
        result.setTotals(totals);
        result.setActiveCurrencyCodes(resolveActiveCurrencyCodes(tenantId, rows));
        return result;
    }

    private static void applyBankAggregates(Map<String, MergedAccount> merged, List<TransactionDTO.SearchAggregateRow> bankRows) {
        for (TransactionDTO.SearchAggregateRow agg : bankRows) {
            if (agg == null || agg.getAccountDbId() == null) {
                continue;
            }
            MergedAccount row = merged.computeIfAbsent(mergeKey(agg), k -> baseMerged(agg));
            row.fromBank = true;
            row.bf = row.bf.add(TransactionMoneyFormat.nz(agg.getBfAmount()));
            row.winLoss = row.winLoss.add(TransactionMoneyFormat.nz(agg.getWinLossAmount()));
            row.periodWinLossCount += agg.getPeriodTxnCount() != null ? agg.getPeriodTxnCount() : 0;
        }
    }

    private static void applyDomainAggregates(Map<String, MergedAccount> merged, List<TransactionDTO.SearchAggregateRow> domainRows) {
        for (TransactionDTO.SearchAggregateRow agg : domainRows) {
            if (agg == null || agg.getAccountDbId() == null) {
                continue;
            }
            MergedAccount row = merged.computeIfAbsent(mergeKey(agg), k -> baseMerged(agg));
            row.fromDomain = true;
            row.bf = row.bf.add(TransactionMoneyFormat.nz(agg.getBfAmount()));
            row.crDr = row.crDr.add(TransactionMoneyFormat.nz(agg.getCrDrAmount()));
            row.periodCrDrCount += agg.getPeriodTxnCount() != null ? agg.getPeriodTxnCount() : 0;
        }
    }

    private static String mergeKey(TransactionDTO.SearchAggregateRow agg) {
        return agg.getAccountDbId() + "|" + trimToEmpty(agg.getCurrencyCode()).toUpperCase(Locale.ROOT);
    }

    private static MergedAccount baseMerged(TransactionDTO.SearchAggregateRow agg) {
        MergedAccount row = new MergedAccount();
        row.accountDbId = agg.getAccountDbId();
        row.accountCode = agg.getAccountCode();
        row.accountName = agg.getAccountName();
        row.role = agg.getRole();
        row.currencyCode = agg.getCurrencyCode();
        row.bf = BigDecimal.ZERO;
        row.winLoss = BigDecimal.ZERO;
        row.crDr = BigDecimal.ZERO;
        return row;
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

    /** One source's aggregates before merge ({@code bankProcess=true} → Win/Loss path). */
    private record SearchSlice(List<TransactionDTO.SearchAggregateRow> aggregates, boolean bankProcess) {
    }

    private static final class MergedAccount {
        private Integer accountDbId;
        private String accountCode;
        private String accountName;
        private String role;
        private String currencyCode;
        private BigDecimal bf;
        private BigDecimal winLoss;
        private BigDecimal crDr;
        private int periodWinLossCount;
        private int periodCrDrCount;
        private boolean fromBank;
        private boolean fromDomain;
    }
}
