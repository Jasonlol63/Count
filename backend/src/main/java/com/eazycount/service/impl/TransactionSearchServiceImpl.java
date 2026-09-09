package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionSearchDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionSearchAggregateRow;
import com.eazycount.dto.TransactionSearchRequest;
import com.eazycount.dto.TransactionSearchResult;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Currency;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionSearchService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/* Win/Loss 和 Cr/Dr 分开算，最后在searchList合并，避免两边逻辑互相污染。*/
@Service
public class TransactionSearchServiceImpl implements TransactionSearchService {

    @Autowired
    private TransactionSearchDao transactionSearchDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private UserDao userDao;

    @Override
    public TransactionSearchResult searchList(TransactionSearchRequest request) {
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

        SearchSlice winLoss = buildWinLossSearchSlice(tenantId, dateFrom, dateTo, currencyCodes, categories);
        SearchSlice domain = buildDomainPaymentSearchSlice(tenantId, dateFrom, dateTo, currencyCodes, categories);

        boolean showAllZeroBalance = Boolean.TRUE.equals(request.getShowAllZeroBalance());
        return mergeSearchSlices(tenantId, winLoss, domain, currencyCodes, categories, showAllZeroBalance, dateTo);
    }

    // ── Win/Loss: Bank Process + Data Capture + manual Adjustment/Profit/Rate-middleman ─────────
    private SearchSlice buildWinLossSearchSlice(
            Integer tenantId,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<String> currencyCodes,
            List<String> categories) {
        List<TransactionSearchAggregateRow> bankRows = transactionSearchDao.aggregateBankProcessWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionSearchAggregateRow> dataCaptureRows = transactionSearchDao.aggregateDataCaptureWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionSearchAggregateRow> adjustmentRows = transactionSearchDao.aggregateManualAdjustmentWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionSearchAggregateRow> profitRows = transactionSearchDao.aggregateManualProfitWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionSearchAggregateRow> rateMiddlemanRows = transactionSearchDao.aggregateManualRateMiddlemanWinLoss(
                tenantId, dateFrom, dateTo, currencyCodes, categories);
        if (bankRows == null) {
            bankRows = List.of();
        }
        if (dataCaptureRows == null) {
            dataCaptureRows = List.of();
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
        List<TransactionSearchAggregateRow> combined = mergeWinLossAggregateRows(bankRows, dataCaptureRows);
        combined = mergeWinLossAggregateRows(combined, adjustmentRows);
        combined = mergeWinLossAggregateRows(combined, profitRows);
        combined = mergeWinLossAggregateRows(combined, rateMiddlemanRows);
        return new SearchSlice(combined, true);
    }

    private static List<TransactionSearchAggregateRow> mergeWinLossAggregateRows(List<TransactionSearchAggregateRow> first,
                                                                                 List<TransactionSearchAggregateRow> second) {
        Map<String, TransactionSearchAggregateRow> merged = new HashMap<>();
        for (TransactionSearchAggregateRow row : first) {
            absorbWinLossAggregate(merged, row);
        }
        for (TransactionSearchAggregateRow row : second) {
            absorbWinLossAggregate(merged, row);
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparing(TransactionSearchAggregateRow::getCurrencyCode,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(TransactionSearchAggregateRow::getAccountCode,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static void absorbWinLossAggregate(Map<String, TransactionSearchAggregateRow> merged,
                                               TransactionSearchAggregateRow row) {
        if (row == null || row.getAccountDbId() == null) {
            return;
        }
        String key = row.getAccountDbId() + "|"
                + trimToEmpty(row.getCurrencyCode()).toUpperCase(Locale.ROOT);
        TransactionSearchAggregateRow existing = merged.get(key);
        if (existing == null) {
            TransactionSearchAggregateRow copy = new TransactionSearchAggregateRow();
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
    private SearchSlice buildDomainPaymentSearchSlice(Integer tenantId, LocalDate dateFrom, LocalDate dateTo,
                                                      List<String> currencyCodes, List<String> categories) {
        List<TransactionSearchAggregateRow> domainRows = transactionSearchDao.aggregateDomainPaymentCrDr(tenantId, dateFrom, dateTo, currencyCodes, categories);
        List<TransactionSearchAggregateRow> rateMiddlemanCrDrRows = transactionSearchDao.aggregateManualRateMiddlemanCrDr(tenantId, dateFrom, dateTo, currencyCodes, categories);
        if (domainRows == null) {
            domainRows = List.of();
        }
        if (rateMiddlemanCrDrRows == null) {
            rateMiddlemanCrDrRows = List.of();
        }
        List<TransactionSearchAggregateRow> combined = new ArrayList<>(domainRows);
        combined.addAll(rateMiddlemanCrDrRows);
        return new SearchSlice(combined, false);
    }

    // ── Merge / present ───────────────────────────────────────────────────────
    private TransactionSearchResult mergeSearchSlices(
            Integer tenantId, SearchSlice winLoss, SearchSlice domain, List<String> currencyCodes,
            List<String> categories, boolean showAllZeroBalance, LocalDate dateTo) {
        Map<String, MergedAccount> merged = new HashMap<>();
        applyWinLossAggregates(merged, winLoss.aggregates());
        applyDomainAggregates(merged, domain.aggregates());

        if (showAllZeroBalance) {
            List<TransactionSearchAggregateRow> shells = transactionSearchDao.findAccountCurrencyShells(
                    tenantId, currencyCodes, categories);
            applyNeverTransactedShells(merged, shells);
        }

        Map<Integer, UserListDTO> accountsById = new HashMap<>();
        List<UserListDTO> tenantAccounts = userDao.findUserByTenantId(tenantId);
        if (tenantAccounts != null) {
            for (UserListDTO account : tenantAccounts) {
                if (account != null && account.getId() != null) {
                    accountsById.put(account.getId(), account);
                }
            }
        }

        List<TransactionSearchResult.Row> rows = new ArrayList<>();
        BigDecimal totalBf = BigDecimal.ZERO;
        BigDecimal totalWl = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;

        for (MergedAccount agg : merged.values()) {
            // Domain-only all-zero with no period activity (e.g. only historical NET PROFIT) → hide
            // Never-transacted shells are kept when showAllZeroBalance requested them.
            if (!agg.fromWinLoss
                    && !agg.neverTransacted
                    && agg.periodCrDrCount <= 0
                    && agg.bf.compareTo(BigDecimal.ZERO) == 0
                    && agg.crDr.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal balance = TransactionMoneyFormat.add(TransactionMoneyFormat.add(agg.bf, agg.winLoss), agg.crDr);

            TransactionSearchResult.Row row = new TransactionSearchResult.Row();
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
            row.setNeverTransacted(agg.neverTransacted);
            row.setAlertActive(computeIsAlert(balance, accountsById.get(agg.accountDbId), dateTo));
            rows.add(row);

            totalBf = totalBf.add(agg.bf);
            totalWl = totalWl.add(agg.winLoss);
            totalCr = totalCr.add(agg.crDr);
        }

        rows.sort(Comparator
                .comparing(TransactionSearchResult.Row::getCurrencyCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(TransactionSearchResult.Row::getAccountCode, Comparator.nullsLast(String::compareTo)));

        TransactionSearchResult.Totals totals = new TransactionSearchResult.Totals();
        totals.setBf(TransactionMoneyFormat.formatMoney(totalBf));
        totals.setWinLoss(TransactionMoneyFormat.formatMoney(totalWl));
        totals.setCrDr(TransactionMoneyFormat.formatMoney(totalCr));
        totals.setBalance(TransactionMoneyFormat.formatMoney(
                TransactionMoneyFormat.add(TransactionMoneyFormat.add(totalBf, totalWl), totalCr)));

        TransactionSearchResult result = new TransactionSearchResult();
        result.setRows(rows);
        result.setTotals(totals);
        result.setActiveCurrencyCodes(resolveActiveCurrencyCodes(tenantId, rows));
        return result;
    }

    /* Payment Alert：与旧版 search_api.php 的 is_alert 判定逻辑一致（金额阈值 + 频率）。 */
    private static boolean computeIsAlert(BigDecimal balance, UserListDTO account, LocalDate dateTo) {
        // 左边列表（balance >= 0）完全不变色
        if (balance.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        if (account == null || account.getPaymentAlert() == null || account.getPaymentAlert() != 1) {
            return false;
        }

        // 条件1：balance <= alert_amount（alert_amount 必须是负数阈值）
        BigDecimal alertAmount = account.getAlertAmount();
        boolean alertAmountMet = alertAmount != null
                && alertAmount.compareTo(BigDecimal.ZERO) < 0
                && balance.compareTo(alertAmount) <= 0;

        // 条件2：alert_type（weekly/monthly/1-31）+ alert_start_date 的变色频率
        String alertType = account.getAlertDay();
        Date alertStartDateRaw = account.getAlertSpecificDate();
        if (!alertAmountMet || alertType == null || alertType.isBlank() || alertStartDateRaw == null) {
            return false;
        }

        LocalDate startDate = Instant.ofEpochMilli(alertStartDateRaw.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        // 开始日期在未来（相对于查询的结束日期），不满足时间条件
        if (startDate.isAfter(dateTo)) {
            return false;
        }

        // 使用搜索日期范围的结束日期（dateTo）判断 alert，而不是当前现实时间，
        // 这样查看历史数据时可以正确显示当时的 alert 状态。
        long daysDiff = ChronoUnit.DAYS.between(startDate, dateTo);
        String alertTypeLower = alertType.trim().toLowerCase(Locale.ROOT);

        if ("weekly".equals(alertTypeLower)) {
            // 从开始日期算起每 7 天再次变色（开始日当天 daysDiff = 0 也会触发）
            return daysDiff % 7 == 0;
        }
        if ("monthly".equals(alertTypeLower)) {
            // 与开始日期是同一天（月份可以不同），不处理大小月边界（与旧版一致）
            return startDate.getDayOfMonth() == dateTo.getDayOfMonth();
        }
        try {
            int daysInterval = Integer.parseInt(alertTypeLower);
            if (daysInterval >= 1 && daysInterval <= 31) {
                return daysDiff % daysInterval == 0;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }

    private static void applyNeverTransactedShells(
            Map<String, MergedAccount> merged,
            List<TransactionSearchAggregateRow> shells) {
        if (shells == null) {
            return;
        }
        for (TransactionSearchAggregateRow shell : shells) {
            if (shell == null || shell.getAccountDbId() == null) {
                continue;
            }
            String key = mergeKey(shell);
            if (merged.containsKey(key)) {
                continue;
            }
            MergedAccount row = baseMerged(shell);
            row.neverTransacted = true;
            merged.put(key, row);
        }
    }

    private static void applyWinLossAggregates(Map<String, MergedAccount> merged, List<TransactionSearchAggregateRow> winLossRows) {
        for (TransactionSearchAggregateRow agg : winLossRows) {
            if (agg == null || agg.getAccountDbId() == null) {
                continue;
            }
            MergedAccount row = merged.computeIfAbsent(mergeKey(agg), k -> baseMerged(agg));
            row.fromWinLoss = true;
            row.bf = row.bf.add(TransactionMoneyFormat.nz(agg.getBfAmount()));
            row.winLoss = row.winLoss.add(TransactionMoneyFormat.nz(agg.getWinLossAmount()));
            row.periodWinLossCount += agg.getPeriodTxnCount() != null ? agg.getPeriodTxnCount() : 0;
        }
    }

    private static void applyDomainAggregates(Map<String, MergedAccount> merged, List<TransactionSearchAggregateRow> domainRows) {
        for (TransactionSearchAggregateRow agg : domainRows) {
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

    private static String mergeKey(TransactionSearchAggregateRow agg) {
        return agg.getAccountDbId() + "|" + trimToEmpty(agg.getCurrencyCode()).toUpperCase(Locale.ROOT);
    }

    private static MergedAccount baseMerged(TransactionSearchAggregateRow agg) {
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

    private List<String> resolveActiveCurrencyCodes(Integer tenantId, List<TransactionSearchResult.Row> rows) {
        Set<String> codes = new LinkedHashSet<>();
        List<Currency> tenantCurrencies = currencyDao.findCurrencyByTenantId(tenantId);
        if (tenantCurrencies != null) {
            for (Currency currency : tenantCurrencies) {
                if (currency != null && currency.getCode() != null && !currency.getCode().isBlank()) {
                    codes.add(currency.getCode().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        for (TransactionSearchResult.Row row : rows) {
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

    /* One source's aggregates before merge (isWinLossSource=true → Win/Loss path). */
    private record SearchSlice(List<TransactionSearchAggregateRow> aggregates, boolean isWinLossSource) {
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
        private boolean fromWinLoss;
        private boolean fromDomain;
        private boolean neverTransacted;
    }
}
