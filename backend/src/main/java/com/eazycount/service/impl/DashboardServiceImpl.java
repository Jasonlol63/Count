package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.DashboardDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.DashboardCurrencyAmountDTO;
import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.TenantOwnership;
import com.eazycount.entity.TenantOwnershipHistory;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DashboardService;
import com.eazycount.service.ExchangeRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String ROLE_PROFIT = "PROFIT";
    private static final String ROLE_EXPENSES = "EXPENSES";
    private static final int EARNINGS_SCALE = 8;

    @Autowired
    private DashboardDao dashboardDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public List<DashboardCurrencyAmountDTO> getKpiCurrencyBreakdown(Integer tenantId, LocalDate dateFrom,
                                                                     LocalDate dateTo, String baseCurrencyCode) {
        requireTenantId(tenantId);
        requireDateRange(dateFrom, dateTo);
        String base = requireBaseCurrency(baseCurrencyCode);

        Tenant tenant = requireTenant(tenantId);
        // GROUP tenants aren't supported yet — same scope as getKpi.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            return List.of();
        }

        List<Integer> tenantIds = List.of(tenantId);
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, Map<String, BigDecimal>> winLossByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateWinLossByRoleAndCurrency(tenantIds, dateFrom, dateTo, roles));
        Map<String, Map<String, BigDecimal>> crDrByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateCrDrByRoleAndCurrency(tenantIds, dateFrom, dateTo, roles));

        // Show every configured currency, not just ones with activity — no-activity rows
        // still render ("—" amount, real rate) instead of disappearing.
        TreeSet<String> currencyCodes = new TreeSet<>();
        for (Currency currency : currencyDao.findCurrencyByTenantId(tenantId)) {
            if (currency.getStatus() == Currency.Status.ACTIVE && currency.getCode() != null) {
                currencyCodes.add(currency.getCode().trim().toUpperCase());
            }
        }
        currencyCodes.addAll(winLossByCurrencyRole.keySet());
        currencyCodes.addAll(crDrByCurrencyRole.keySet());
        currencyCodes.add(base);

        // One rate lookup for the whole request, reused per row — no per-currency query.
        Map<String, BigDecimal> ratesToUsd = exchangeRateService.loadRatesToUsd();

        // Earning tab: same ownership resolution as the KPI card's Earnings figure (§11 in
        // dashboard-springboot-kpi.md), resolved once here, not once per currency.
        String ownerType = resolveOwnerType();
        BigDecimal earningsPercentage = ownerType != null
                ? resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, true)
                : null;

        List<DashboardCurrencyAmountDTO> rows = new ArrayList<>();
        for (String code : currencyCodes) {
            boolean hasActivity = winLossByCurrencyRole.containsKey(code) || crDrByCurrencyRole.containsKey(code);
            BigDecimal netProfit;
            if (hasActivity || code.equals(base)) {
                // Base currency always resolves to a number (0 if no activity); other
                // currencies with no activity stay null so the frontend renders "—" not 0.
                Map<String, BigDecimal> winLossByRole = winLossByCurrencyRole.getOrDefault(code, Map.of());
                Map<String, BigDecimal> crDrByRole = crDrByCurrencyRole.getOrDefault(code, Map.of());
                BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                netProfit = profit.add(expenses);
            } else {
                netProfit = null;
            }

            // Rate is always resolved, regardless of activity.
            BigDecimal amount = netProfit != null
                    ? exchangeRateService.convert(netProfit, code, base, ratesToUsd)
                    : null;
            BigDecimal rate = exchangeRateService.convert(BigDecimal.ONE, code, base, ratesToUsd);

            // Earning tab shows 0 (not "—") for no activity/no ownership — "nothing earned"
            // is a real, displayable answer here.
            BigDecimal earnings = earningsPercentage != null && earningsPercentage.compareTo(BigDecimal.ZERO) > 0
                    ? earningsFrom(netProfit != null ? netProfit : BigDecimal.ZERO, earningsPercentage)
                    : BigDecimal.ZERO;
            BigDecimal earningsConverted = exchangeRateService.convert(earnings, code, base, ratesToUsd);

            rows.add(new DashboardCurrencyAmountDTO(code, netProfit, amount, rate, earnings, earningsConverted));
        }
        return rows;
    }

    @Override
    public List<DashboardCurrencyAmountDTO> getKpiCurrencyBreakdownForCompanies(
            List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String baseCurrencyCode) {
        requireTenantIds(tenantIds);
        requireDateRange(dateFrom, dateTo);
        String base = requireBaseCurrency(baseCurrencyCode);

        // Same per-currency queries as getKpiCurrencyBreakdown(), just given the full
        // tenantIds — SUM(...) already aggregates across tenants, no rollup query needed.
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, Map<String, BigDecimal>> winLossByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateWinLossByRoleAndCurrency(tenantIds, dateFrom, dateTo, roles));
        Map<String, Map<String, BigDecimal>> crDrByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateCrDrByRoleAndCurrency(tenantIds, dateFrom, dateTo, roles));

        // Union of every company's configured currencies (one batch query) + active
        // currencies + the base currency.
        TreeSet<String> currencyCodes = new TreeSet<>();
        for (Currency currency : currencyDao.findCurrencyByTenantIds(tenantIds)) {
            if (currency.getStatus() == Currency.Status.ACTIVE && currency.getCode() != null) {
                currencyCodes.add(currency.getCode().trim().toUpperCase());
            }
        }
        currencyCodes.addAll(winLossByCurrencyRole.keySet());
        currencyCodes.addAll(crDrByCurrencyRole.keySet());
        currencyCodes.add(base);

        Map<String, BigDecimal> ratesToUsd = exchangeRateService.loadRatesToUsd();

        // Earning tab: same rule as the KPI card's Earnings (computeCompaniesEarnings) and the
        // Trend Chart's Earnings line (applyCompaniesTrendEarnings) — each company resolves its
        // own effective percentage independently and contributes its own Net Profit × that
        // percentage; NOT one shared percentage over the combined total, since companies in
        // this list can have different direct-or-cascade ownership paths. Per-company-per-
        // currency figures and percentages are each one batched query, not one per company.
        String ownerType = resolveOwnerType();
        Map<Integer, BigDecimal> percentageByTenant = ownerType != null
                ? resolveEffectiveEarningsPercentagesForTenants(tenantIds, dateTo, ownerType, true)
                : Map.of();
        Map<Integer, Map<String, Map<String, BigDecimal>>> winLossByTenantCurrencyRole = percentageByTenant.isEmpty()
                ? Map.of() : toTenantCurrencyRoleMap(
                        dashboardDao.aggregateWinLossByRoleAndTenantAndCurrency(tenantIds, dateFrom, dateTo, roles));
        Map<Integer, Map<String, Map<String, BigDecimal>>> crDrByTenantCurrencyRole = percentageByTenant.isEmpty()
                ? Map.of() : toTenantCurrencyRoleMap(
                        dashboardDao.aggregateCrDrByRoleAndTenantAndCurrency(tenantIds, dateFrom, dateTo, roles));

        List<DashboardCurrencyAmountDTO> rows = new ArrayList<>();
        for (String code : currencyCodes) {
            boolean hasActivity = winLossByCurrencyRole.containsKey(code) || crDrByCurrencyRole.containsKey(code);
            BigDecimal netProfit;
            if (hasActivity || code.equals(base)) {
                Map<String, BigDecimal> winLossByRole = winLossByCurrencyRole.getOrDefault(code, Map.of());
                Map<String, BigDecimal> crDrByRole = crDrByCurrencyRole.getOrDefault(code, Map.of());
                BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                netProfit = profit.add(expenses);
            } else {
                netProfit = null;
            }

            BigDecimal amount = netProfit != null
                    ? exchangeRateService.convert(netProfit, code, base, ratesToUsd)
                    : null;
            BigDecimal rate = exchangeRateService.convert(BigDecimal.ONE, code, base, ratesToUsd);

            BigDecimal earnings = sumWeightedGroupProfit(tenantIds, percentageByTenant, companyTenantId -> {
                Map<String, BigDecimal> companyWinLossByRole = winLossByTenantCurrencyRole
                        .getOrDefault(companyTenantId, Map.of()).getOrDefault(code, Map.of());
                Map<String, BigDecimal> companyCrDrByRole = crDrByTenantCurrencyRole
                        .getOrDefault(companyTenantId, Map.of()).getOrDefault(code, Map.of());
                BigDecimal companyProfit = amountForRole(ROLE_PROFIT, companyWinLossByRole, companyCrDrByRole);
                BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, companyWinLossByRole, companyCrDrByRole);
                return companyProfit.add(companyExpenses);
            });
            BigDecimal earningsConverted = exchangeRateService.convert(earnings, code, base, ratesToUsd);

            rows.add(new DashboardCurrencyAmountDTO(code, netProfit, amount, rate, earnings, earningsConverted));
        }
        return rows;
    }

    @Override
    public DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        requireTenantId(tenantId);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);

        Tenant tenant = requireTenant(tenantId);

        // GROUP tenants aren't supported yet — empty KPI, no Earnings. Kept out of
        // buildKpiDto() on purpose: getKpiForGroup()'s equivalent check throws instead, so
        // sharing this would need its own branching for no real benefit.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            DashboardKpiDTO empty = new DashboardKpiDTO();
            empty.setShowEarnings(false);
            return empty;
        }

        List<Integer> tenantIds = List.of(tenantId);
        return buildKpiDto(tenantId, dateFrom, dateTo, true,
                (from, to) -> computeProfitExpenses(tenantIds, from, to, currency));
    }

    @Override
    public DashboardKpiDTO getKpiForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                          LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        requireGroupTenantId(groupTenantId);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);
        // No company list → treat as no member companies (Group Profit = 0, not an error).
        List<Integer> companies = orEmpty(companyTenantIds);

        requireGroupTenant(groupTenantId);

        // allowGroupCascade=false: a Group's own Earnings never falls back through another Group.
        return buildKpiDto(groupTenantId, dateFrom, dateTo, false,
                (from, to) -> computeGroupKpi(groupTenantId, companies, from, to, currency));
    }

    /*
     * Shared skeleton for getKpi()/getKpiForGroup(): current + previous-period P&L (via
     * profitExpensesFn, the one differing step), then Earnings applied the same way for
     * both. Tenant lookup/type validation stays with each caller — see getKpi() for why.
     */
    private DashboardKpiDTO buildKpiDto(Integer ownershipTenantId, LocalDate dateFrom, LocalDate dateTo,
                                        boolean allowGroupCascade,
                                        BiFunction<LocalDate, LocalDate, ProfitExpenses> profitExpensesFn) {
        DashboardKpiDTO dto = new DashboardKpiDTO();

        ProfitExpenses current = profitExpensesFn.apply(dateFrom, dateTo);
        dto.setProfit(current.profit);
        dto.setExpenses(current.expenses);
        dto.setNetProfit(current.netProfit);
        applyEarnings(dto, ownershipTenantId, dateTo, current.netProfit, allowGroupCascade);

        LocalDate[] previousRange = resolvePreviousRange(dateFrom, dateTo);
        LocalDate previousDateFrom = previousRange[0];
        LocalDate previousDateTo = previousRange[1];
        dto.setPreviousDateFrom(previousDateFrom);
        dto.setPreviousDateTo(previousDateTo);

        ProfitExpenses previous = profitExpensesFn.apply(previousDateFrom, previousDateTo);
        dto.setPreviousProfit(previous.profit);
        dto.setPreviousExpenses(previous.expenses);
        dto.setPreviousNetProfit(previous.netProfit);
        if (dto.isShowEarnings()) {
            dto.setPreviousEarnings(
                    resolveEarningsAmount(ownershipTenantId, previousDateTo, previous.netProfit, allowGroupCascade));
        }

        return dto;
    }

    @Override
    public DashboardKpiDTO getKpiForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo,
                                              String currencyCode) {
        requireTenantIds(tenantIds);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);

        // "Company: All" rollup — scope (which tenant ids) is resolved by the caller.
        // No previous-period comparison for this view yet (follow-up).
        ProfitExpenses totals = computeProfitExpenses(tenantIds, dateFrom, dateTo, currency);
        DashboardKpiDTO dto = new DashboardKpiDTO();
        dto.setProfit(totals.profit);
        dto.setExpenses(totals.expenses);
        dto.setNetProfit(totals.netProfit);

        // Earnings = Σ each company's own Net Profit × its own effective %, NOT
        // totals.netProfit × one shared % — companies can have different ownership paths
        // (see computeCompaniesEarnings).
        String ownerType = resolveOwnerType();
        BigDecimal earnings = ownerType != null
                ? computeCompaniesEarnings(tenantIds, dateFrom, dateTo, currency, ownerType)
                : null;
        if (earnings != null) {
            dto.setShowEarnings(true);
            dto.setEarnings(earnings);
        } else {
            dto.setShowEarnings(false);
        }
        return dto;
    }

    @Override
    public List<DashboardCurrencyAmountDTO.CompanyNetProfit> getGroupCompanyNetProfitBreakdown(
            Integer groupTenantId, List<Integer> companyTenantIds,
            LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        requireGroupTenantId(groupTenantId);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);
        List<Integer> companies = orEmpty(companyTenantIds);

        Tenant tenant = requireGroupTenant(groupTenantId);
        if (companies.isEmpty()) {
            return List.of();
        }

        // Same batched-by-tenant queries computeGroupProfit() uses, reused here unweighted
        // (one query, not one per company).
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<Integer, Map<String, BigDecimal>> winLossByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateWinLossByRoleAndTenant(companies, dateFrom, dateTo, roles, currency));
        Map<Integer, Map<String, BigDecimal>> crDrByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateCrDrByRoleAndTenant(companies, dateFrom, dateTo, roles, currency));

        // One batch lookup for display codes, not one findTenantById per row.
        Map<Integer, String> companyCodeByTenantId = new HashMap<>();
        for (Tenant company : tenantDao.findTenantsByIds(companies)) {
            companyCodeByTenantId.put(company.getId(), company.getCode());
        }

        List<DashboardCurrencyAmountDTO.CompanyNetProfit> rows = new ArrayList<>();
        for (Integer companyTenantId : companies) {
            Map<String, BigDecimal> winLossByRole = winLossByTenantRole.getOrDefault(companyTenantId, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByTenantRole.getOrDefault(companyTenantId, Map.of());
            BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal netProfit = profit.add(expenses);

            String code = companyCodeByTenantId.getOrDefault(companyTenantId, String.valueOf(companyTenantId));
            rows.add(new DashboardCurrencyAmountDTO.CompanyNetProfit(code, netProfit, tenant.getCode()));
        }
        return rows;
    }

    @Override
    public List<DashboardCurrencyAmountDTO> getGroupKpiCurrencyBreakdown(
            Integer groupTenantId, List<Integer> companyTenantIds,
            LocalDate dateFrom, LocalDate dateTo, String baseCurrencyCode) {
        requireGroupTenantId(groupTenantId);
        requireDateRange(dateFrom, dateTo);
        String base = requireBaseCurrency(baseCurrencyCode);
        List<Integer> companies = orEmpty(companyTenantIds);

        requireGroupTenant(groupTenantId);

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);

        // Per-company, per-currency Win/Loss + Cr/Dr — empty companies means empty maps,
        // same "no members -> 0" rule as computeGroupProfit().
        Map<Integer, Map<String, Map<String, BigDecimal>>> winLossByTenantCurrencyRole =
                companies.isEmpty() ? Map.of() : toTenantCurrencyRoleMap(
                        dashboardDao.aggregateWinLossByRoleAndTenantAndCurrency(companies, dateFrom, dateTo, roles));
        Map<Integer, Map<String, Map<String, BigDecimal>>> crDrByTenantCurrencyRole =
                companies.isEmpty() ? Map.of() : toTenantCurrencyRoleMap(
                        dashboardDao.aggregateCrDrByRoleAndTenantAndCurrency(companies, dateFrom, dateTo, roles));
        Map<Integer, BigDecimal> equityPercentageByTenant =
                companies.isEmpty() ? Map.of() : findGroupEquityPercentages(companies, groupTenantId, dateTo);

        // Group's own ledger per currency — only its Expenses count (same rule as computeGroupKpi()).
        Map<String, Map<String, BigDecimal>> groupOwnWinLossByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateWinLossByRoleAndCurrency(List.of(groupTenantId), dateFrom, dateTo, roles));
        Map<String, Map<String, BigDecimal>> groupOwnCrDrByCurrencyRole = toCurrencyRoleMap(
                dashboardDao.aggregateCrDrByRoleAndCurrency(List.of(groupTenantId), dateFrom, dateTo, roles));

        // Active currencies (member company or Group's own ledger) vs. every configured
        // currency (shown even with no activity, "—" amount).
        TreeSet<String> currencyCodesWithActivity = new TreeSet<>();
        for (Map<String, Map<String, BigDecimal>> byCurrency : winLossByTenantCurrencyRole.values()) {
            currencyCodesWithActivity.addAll(byCurrency.keySet());
        }
        for (Map<String, Map<String, BigDecimal>> byCurrency : crDrByTenantCurrencyRole.values()) {
            currencyCodesWithActivity.addAll(byCurrency.keySet());
        }
        currencyCodesWithActivity.addAll(groupOwnWinLossByCurrencyRole.keySet());
        currencyCodesWithActivity.addAll(groupOwnCrDrByCurrencyRole.keySet());

        TreeSet<String> currencyCodes = new TreeSet<>(currencyCodesWithActivity);
        for (Currency currency : currencyDao.findCurrencyByTenantId(groupTenantId)) {
            if (currency.getStatus() == Currency.Status.ACTIVE && currency.getCode() != null) {
                currencyCodes.add(currency.getCode().trim().toUpperCase());
            }
        }
        currencyCodes.add(base);

        Map<String, BigDecimal> ratesToUsd = exchangeRateService.loadRatesToUsd();

        // Group's own Earning tab: direct ownership only (allowGroupCascade=false, same as
        // getKpiForGroup()), resolved once. Whether the tab renders is a frontend concern.
        String ownerType = resolveOwnerType();
        BigDecimal earningsPercentage = ownerType != null
                ? resolveEffectiveEarningsPercentage(groupTenantId, dateTo, ownerType, false)
                : null;

        List<DashboardCurrencyAmountDTO> rows = new ArrayList<>();
        for (String code : currencyCodes) {
            BigDecimal netProfit;
            if (currencyCodesWithActivity.contains(code) || code.equals(base)) {
                // Group Profit(code) = Σ (company's Net Profit in `code` × equity %) —
                // same rule as computeGroupProfit(), per currency instead of one total.
                BigDecimal groupProfit = sumWeightedGroupProfit(companies, equityPercentageByTenant, companyTenantId -> {
                    Map<String, BigDecimal> winLossByRole = winLossByTenantCurrencyRole
                            .getOrDefault(companyTenantId, Map.of()).getOrDefault(code, Map.of());
                    Map<String, BigDecimal> crDrByRole = crDrByTenantCurrencyRole
                            .getOrDefault(companyTenantId, Map.of()).getOrDefault(code, Map.of());
                    BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                    BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                    return companyProfit.add(companyExpenses);
                });

                BigDecimal groupExpenses = amountForRole(ROLE_EXPENSES,
                        groupOwnWinLossByCurrencyRole.getOrDefault(code, Map.of()),
                        groupOwnCrDrByCurrencyRole.getOrDefault(code, Map.of()));
                netProfit = groupProfit.add(groupExpenses);
            } else {
                netProfit = null;
            }

            BigDecimal amount = netProfit != null
                    ? exchangeRateService.convert(netProfit, code, base, ratesToUsd)
                    : null;
            BigDecimal rate = exchangeRateService.convert(BigDecimal.ONE, code, base, ratesToUsd);

            BigDecimal earnings = earningsPercentage != null && earningsPercentage.compareTo(BigDecimal.ZERO) > 0
                    ? earningsFrom(netProfit != null ? netProfit : BigDecimal.ZERO, earningsPercentage)
                    : BigDecimal.ZERO;
            BigDecimal earningsConverted = exchangeRateService.convert(earnings, code, base, ratesToUsd);

            rows.add(new DashboardCurrencyAmountDTO(code, netProfit, amount, rate, earnings, earningsConverted));
        }
        return rows;
    }

    @Override
    public List<DashboardTrendPointDTO> getTrend(Integer tenantId, LocalDate dateFrom, LocalDate dateTo,
                                                  String currencyCode) {
        requireTenantId(tenantId);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);

        Tenant tenant = requireTenant(tenantId);

        // GROUP tenants aren't supported yet — empty series; frontend falls back to zero rows.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            return List.of();
        }

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        List<Integer> tenantIds = List.of(tenantId);
        Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));

        List<DashboardTrendPointDTO> points = buildTrendPoints(dateFrom, dateTo, winLossByDateRole, crDrByDateRole);

        // No ownership standing (member/ledger login) → skip this query, leave the line null.
        String ownerType = resolveOwnerType();
        if (ownerType != null) {
            Map<YearMonth, BigDecimal> percentageByMonth =
                    resolveEffectiveEarningsPercentagesByMonth(tenantId, ownerType, dateFrom, dateTo, true);
            applyTrendEarnings(points, percentageByMonth);
        }

        return points;
    }

    @Override
    public List<DashboardTrendPointDTO> getTrendForCompanies(List<Integer> tenantIds, LocalDate dateFrom,
                                                               LocalDate dateTo, String currencyCode) {
        requireTenantIds(tenantIds);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));

        List<DashboardTrendPointDTO> points = buildTrendPoints(dateFrom, dateTo, winLossByDateRole, crDrByDateRole);

        // Earnings line (§18): each company resolves its own ownership per month, same rule
        // as the KPI card (§17), per day instead of one total.
        String ownerType = resolveOwnerType();
        if (ownerType != null) {
            applyCompaniesTrendEarnings(points, tenantIds, dateFrom, dateTo, currency, ownerType);
        }
        return points;
    }

    @Override
    public List<DashboardTrendPointDTO> getTrendForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                                           LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        requireGroupTenantId(groupTenantId);
        requireDateRange(dateFrom, dateTo);
        String currency = requireCurrency(currencyCode);
        List<Integer> companies = orEmpty(companyTenantIds);

        requireGroupTenant(groupTenantId);

        List<DashboardTrendPointDTO> points = buildGroupTrendPoints(groupTenantId, companies, dateFrom, dateTo, currency);

        // allowGroupCascade=false: a Group's own Earnings never falls back through another Group.
        String ownerType = resolveOwnerType();
        if (ownerType != null) {
            Map<YearMonth, BigDecimal> percentageByMonth = resolveEffectiveEarningsPercentagesByMonth(
                    groupTenantId, ownerType, dateFrom, dateTo, false);
            applyTrendEarnings(points, percentageByMonth);
        }

        return points;
    }

    /*
     * Company: All Earnings — Σ of (each company's Net Profit × its own effective %), each
     * resolved independently (direct or Group cascade), batched into a fixed query count. A
     * company with no percentage simply doesn't contribute. Returns null when none do, so
     * the caller can set showEarnings=false.
     */
    private BigDecimal computeCompaniesEarnings(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo,
                                                String currency, String ownerType) {
        Map<Integer, BigDecimal> percentageByTenant =
                resolveEffectiveEarningsPercentagesForTenants(tenantIds, dateTo, ownerType, true);
        if (percentageByTenant.isEmpty()) {
            return null;
        }

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<Integer, Map<String, BigDecimal>> winLossByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateWinLossByRoleAndTenant(tenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, Map<String, BigDecimal>> crDrByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateCrDrByRoleAndTenant(tenantIds, dateFrom, dateTo, roles, currency));

        BigDecimal totalEarnings = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : percentageByTenant.entrySet()) {
            Integer tenantId = entry.getKey();
            BigDecimal percentage = entry.getValue();
            Map<String, BigDecimal> winLossByRole = winLossByTenantRole.getOrDefault(tenantId, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByTenantRole.getOrDefault(tenantId, Map.of());
            BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal companyNetProfit = profit.add(expenses);
            totalEarnings = totalEarnings.add(earningsFrom(companyNetProfit, percentage));
        }
        return totalEarnings;
    }

    private static Map<String, Map<String, BigDecimal>> toCurrencyRoleMap(List<DashboardKpiDTO.RoleAmount> rows) {
        Map<String, Map<String, BigDecimal>> byCurrency = new HashMap<>();
        for (DashboardKpiDTO.RoleAmount row : rows) {
            String code = row.getCurrencyCode() == null ? "" : row.getCurrencyCode().trim().toUpperCase();
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byCurrency.computeIfAbsent(code, c -> new HashMap<>()).merge(role, row.getAmount(), BigDecimal::add);
        }
        return byCurrency;
    }

    /*
     * Σ over companies of (Net Profit × equity %) — shared by computeGroupProfit(),
     * getGroupKpiCurrencyBreakdown() and buildGroupTrendPoints(); companyNetProfitFn supplies
     * the one differing step: how to look up a company's Net Profit.
     */
    private static BigDecimal sumWeightedGroupProfit(List<Integer> companyTenantIds,
                                                     Map<Integer, BigDecimal> equityPercentageByTenant, Function<Integer, BigDecimal> companyNetProfitFn) {
        BigDecimal total = BigDecimal.ZERO;
        for (Integer tenantId : companyTenantIds) {
            BigDecimal percentage = equityPercentageByTenant.get(tenantId);
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) == 0) {
                continue; // No equity allocated — contributes 0.
            }
            BigDecimal companyNetProfit = companyNetProfitFn.apply(tenantId);
            total = total.add(companyNetProfit
                    .multiply(percentage)
                    .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
        }
        return total;
    }

    private static Map<Integer, Map<String, Map<String, BigDecimal>>> toTenantCurrencyRoleMap(
            List<DashboardKpiDTO.RoleAmount> rows) {
        Map<Integer, Map<String, Map<String, BigDecimal>>> byTenant = new HashMap<>();
        for (DashboardKpiDTO.RoleAmount row : rows) {
            String code = row.getCurrencyCode() == null ? "" : row.getCurrencyCode().trim().toUpperCase();
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byTenant.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                    .computeIfAbsent(code, c -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byTenant;
    }

    /* Group Profit (weighted rollup of member companies) + Group Expenses (Group's own ledger). */
    private ProfitExpenses computeGroupKpi(Integer groupTenantId, List<Integer> companyTenantIds,
                                           LocalDate dateFrom, LocalDate dateTo, String currency) {
        BigDecimal groupProfit = computeGroupProfit(companyTenantIds, groupTenantId, dateFrom, dateTo, currency);
        // Only Expenses come from the Group's own ledger — Group Profit only comes from the
        // member rollup (business rule, not an oversight).
        BigDecimal groupExpenses = computeProfitExpenses(List.of(groupTenantId), dateFrom, dateTo, currency).expenses;
        BigDecimal groupNetProfit = groupProfit.add(groupExpenses);
        return new ProfitExpenses(groupProfit, groupExpenses, groupNetProfit);
    }

    /*
     * Group Profit = Σ (member's Net Profit × its equity %). Net Profits and equity % are
     * each one batch query, then joined and weighted in Java — no third query.
     */
    private BigDecimal computeGroupProfit(List<Integer> companyTenantIds, Integer groupTenantId,
                                          LocalDate dateFrom, LocalDate dateTo, String currency) {
        if (companyTenantIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<Integer, Map<String, BigDecimal>> winLossByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateWinLossByRoleAndTenant(companyTenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, Map<String, BigDecimal>> crDrByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateCrDrByRoleAndTenant(companyTenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, BigDecimal> equityPercentageByTenant =
                findGroupEquityPercentages(companyTenantIds, groupTenantId, dateTo);

        return sumWeightedGroupProfit(companyTenantIds, equityPercentageByTenant, tenantId -> {
            Map<String, BigDecimal> winLossByRole = winLossByTenantRole.getOrDefault(tenantId, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByTenantRole.getOrDefault(tenantId, Map.of());
            BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            return companyProfit.add(companyExpenses);
        });
    }

    /* Single-date view of resolveGroupEquityPercentagesByMonth — a thin wrapper, not a
     * separate query (a single date is just a one-month range). */
    private Map<Integer, BigDecimal> findGroupEquityPercentages(List<Integer> companyTenantIds,
                                                                Integer groupTenantId, LocalDate dateTo) {
        return resolveGroupEquityPercentagesByMonth(companyTenantIds, groupTenantId, dateTo, dateTo)
                .getOrDefault(YearMonth.from(dateTo), Map.of());
    }

    /*
     * Group Trend Chart: same algorithm as computeGroupKpi(), one point per day. Daily
     * company figures come from the *AndTenantAndDate queries; equity % is batched per
     * month, not one flat % for the whole range.
     */
    private List<DashboardTrendPointDTO> buildGroupTrendPoints(Integer groupTenantId, List<Integer> companyTenantIds,
                                                                LocalDate dateFrom, LocalDate dateTo, String currency) {
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);

        List<Integer> groupTenantIds = List.of(groupTenantId);
        Map<LocalDate, Map<String, BigDecimal>> groupWinLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(groupTenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> groupCrDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(groupTenantIds, dateFrom, dateTo, roles, currency));

        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> companyWinLossByTenantDateRole = Map.of();
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> companyCrDrByTenantDateRole = Map.of();
        Map<YearMonth, Map<Integer, BigDecimal>> equityPercentageByMonth = Map.of();
        if (!companyTenantIds.isEmpty()) {
            companyWinLossByTenantDateRole = toTenantDateRoleMap(
                    dashboardDao.aggregateWinLossByRoleAndTenantAndDate(companyTenantIds, dateFrom, dateTo, roles, currency));
            companyCrDrByTenantDateRole = toTenantDateRoleMap(
                    dashboardDao.aggregateCrDrByRoleAndTenantAndDate(companyTenantIds, dateFrom, dateTo, roles, currency));
            equityPercentageByMonth = resolveGroupEquityPercentagesByMonth(companyTenantIds, groupTenantId, dateFrom, dateTo);
        }
        // Captured as final so the lambda below can reference them (they're reassigned above).
        final Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> winLossByTenantDateRole = companyWinLossByTenantDateRole;
        final Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> crDrByTenantDateRole = companyCrDrByTenantDateRole;

        List<DashboardTrendPointDTO> points = new ArrayList<>();
        for (LocalDate date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            LocalDate currentDate = date;
            Map<Integer, BigDecimal> percentageByTenant =
                    equityPercentageByMonth.getOrDefault(YearMonth.from(date), Map.of());

            BigDecimal groupProfit = sumWeightedGroupProfit(companyTenantIds, percentageByTenant, tenantId -> {
                Map<String, BigDecimal> winLossByRole = winLossByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(currentDate, Map.of());
                Map<String, BigDecimal> crDrByRole = crDrByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(currentDate, Map.of());
                BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                return companyProfit.add(companyExpenses);
            });

            Map<String, BigDecimal> groupWinLossByRole = groupWinLossByDateRole.getOrDefault(date, Map.of());
            Map<String, BigDecimal> groupCrDrByRole = groupCrDrByDateRole.getOrDefault(date, Map.of());
            BigDecimal groupExpenses = amountForRole(ROLE_EXPENSES, groupWinLossByRole, groupCrDrByRole);
            BigDecimal groupNetProfit = groupProfit.add(groupExpenses);

            points.add(new DashboardTrendPointDTO(date, groupProfit, groupExpenses, groupNetProfit, null));
        }
        return points;
    }

    private static Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> toTenantDateRoleMap(
            List<DashboardTrendPointDTO.RoleAmount> rows) {
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> byTenant = new HashMap<>();
        for (DashboardTrendPointDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byTenant.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                    .computeIfAbsent(row.getDate(), d -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byTenant;
    }

    /* Trend Chart Earnings line — same company->Group fallback as
     * resolveEffectiveEarningsPercentage, per month. Thin wrapper over
     * resolveEffectiveEarningsPercentagesForTenantsByMonth. */
    private Map<YearMonth, BigDecimal> resolveEffectiveEarningsPercentagesByMonth(Integer tenantId,
            String ownerType, LocalDate dateFrom, LocalDate dateTo, boolean allowGroupCascade) {
        return resolveEffectiveEarningsPercentagesForTenantsByMonth(
                        List.of(tenantId), dateFrom, dateTo, ownerType, allowGroupCascade)
                .getOrDefault(tenantId, Map.of());
    }

    /*
     * Batched-tenants x batched-months ownership lookup — same live/history split, one pair
     * of queries for a whole tenant list instead of one at a time. Used for both companies'
     * own ownership and (later) the login's ownership in cascaded Group tenants.
     */
    private Map<Integer, Map<YearMonth, BigDecimal>> resolveOwnershipPercentagesForTenantsByMonth(
            List<Integer> tenantIds, Integer accountId, String ownerType, LocalDate dateFrom, LocalDate dateTo) {
        Map<Integer, Map<YearMonth, BigDecimal>> percentageByTenantAndMonth = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return percentageByTenantAndMonth;
        }
        YearMonth currentMonth = YearMonth.now();
        List<LocalDate> historyMonths = new ArrayList<>();
        for (YearMonth month = YearMonth.from(dateFrom); !month.isAfter(YearMonth.from(dateTo)); month = month.plusMonths(1)) {
            if (!month.equals(currentMonth)) {
                historyMonths.add(month.atDay(1));
            }
        }
        if (!historyMonths.isEmpty()) {
            for (TenantOwnershipHistory row : dashboardDao.findOwnershipPercentagesForTenantsAndMonths(
                    tenantIds, accountId, ownerType, historyMonths)) {
                percentageByTenantAndMonth.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                        .put(YearMonth.from(row.getEffectiveMonth()), row.getPercentage());
            }
        }
        if (!YearMonth.from(dateFrom).isAfter(currentMonth) && !currentMonth.isAfter(YearMonth.from(dateTo))) {
            for (TenantOwnership row : dashboardDao.findLiveOwnershipForTenants(tenantIds, accountId, ownerType)) {
                percentageByTenantAndMonth.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                        .put(currentMonth, row.getPercentage());
            }
        }
        return percentageByTenantAndMonth;
    }

    /* Batched-tenants x batched-months version of the Group-allocation lookup. */
    private Map<Integer, Map<YearMonth, GroupAllocation>> resolveCompanyGroupAllocationsForTenantsByMonth(
            List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo) {
        Map<Integer, Map<YearMonth, GroupAllocation>> allocationByTenantAndMonth = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return allocationByTenantAndMonth;
        }
        YearMonth currentMonth = YearMonth.now();
        List<LocalDate> historyMonths = new ArrayList<>();
        for (YearMonth month = YearMonth.from(dateFrom); !month.isAfter(YearMonth.from(dateTo)); month = month.plusMonths(1)) {
            if (!month.equals(currentMonth)) {
                historyMonths.add(month.atDay(1));
            }
        }
        if (!historyMonths.isEmpty()) {
            for (TenantOwnershipHistory row : dashboardDao.findCompanyGroupAllocationsForTenantsAndMonths(tenantIds, historyMonths)) {
                allocationByTenantAndMonth.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                        .put(YearMonth.from(row.getEffectiveMonth()),
                                new GroupAllocation(row.getPartnerTenantId(), row.getPercentage()));
            }
        }
        if (!YearMonth.from(dateFrom).isAfter(currentMonth) && !currentMonth.isAfter(YearMonth.from(dateTo))) {
            for (TenantOwnership row : dashboardDao.findCompanyGroupAllocationsForTenants(tenantIds)) {
                allocationByTenantAndMonth.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                        .put(currentMonth, new GroupAllocation(row.getPartnerTenantId(), row.getPercentage()));
            }
        }
        return allocationByTenantAndMonth;
    }

    /*
     * Batched version of resolveEffectiveEarningsPercentagesByMonth — same direct-then-cascade
     * rule per company per month, query count stays fixed regardless of company/month count
     * (§18). A missing tenant/month entry contributes 0.
     */
    private Map<Integer, Map<YearMonth, BigDecimal>> resolveEffectiveEarningsPercentagesForTenantsByMonth(
            List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String ownerType, boolean allowGroupCascade) {
        Integer accountId = SecurityUtils.currentUser().user_id;
        Map<Integer, Map<YearMonth, BigDecimal>> directByTenant =
                resolveOwnershipPercentagesForTenantsByMonth(tenantIds, accountId, ownerType, dateFrom, dateTo);
        if (!allowGroupCascade) {
            return directByTenant;
        }

        Map<Integer, Map<YearMonth, GroupAllocation>> allocationByTenant =
                resolveCompanyGroupAllocationsForTenantsByMonth(tenantIds, dateFrom, dateTo);
        if (allocationByTenant.isEmpty()) {
            return directByTenant;
        }

        // Every distinct Group tenant id referenced — usually just one, since a company
        // allocates to at most one Group.
        List<Integer> groupTenantIds = allocationByTenant.values().stream()
                .flatMap(monthMap -> monthMap.values().stream())
                .map(allocation -> allocation.groupTenantId)
                .distinct()
                .toList();
        Map<Integer, Map<YearMonth, BigDecimal>> groupDirectByTenant =
                resolveOwnershipPercentagesForTenantsByMonth(groupTenantIds, accountId, ownerType, dateFrom, dateTo);

        Map<Integer, Map<YearMonth, BigDecimal>> result = new HashMap<>();
        for (Integer tenantId : tenantIds) {
            Map<YearMonth, BigDecimal> direct = directByTenant.getOrDefault(tenantId, Map.of());
            Map<YearMonth, GroupAllocation> allocationByMonth = allocationByTenant.getOrDefault(tenantId, Map.of());
            Map<YearMonth, BigDecimal> combined = new HashMap<>(direct);
            for (Map.Entry<YearMonth, GroupAllocation> entry : allocationByMonth.entrySet()) {
                YearMonth month = entry.getKey();
                BigDecimal existingDirect = direct.get(month);
                if (existingDirect != null && existingDirect.compareTo(BigDecimal.ZERO) > 0) {
                    continue; // Direct ownership already covers this company/month.
                }
                GroupAllocation allocation = entry.getValue();
                if (allocation.percentage.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal groupShare = groupDirectByTenant.getOrDefault(allocation.groupTenantId, Map.of()).get(month);
                if (groupShare == null || groupShare.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                combined.put(month, allocation.percentage
                        .multiply(groupShare)
                        .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
            }
            if (!combined.isEmpty()) {
                result.put(tenantId, combined);
            }
        }
        return result;
    }

    /*
     * Company: All Trend Earnings — per day, Σ (company's Net Profit that day × its own %
     * for that month). Needs per-company figures, not the summed point.netProfit, since
     * companies can have different percentages the same day.
     */
    private void applyCompaniesTrendEarnings(List<DashboardTrendPointDTO> points, List<Integer> tenantIds,
                                              LocalDate dateFrom, LocalDate dateTo, String currency, String ownerType) {
        Map<Integer, Map<YearMonth, BigDecimal>> percentageByTenantAndMonth =
                resolveEffectiveEarningsPercentagesForTenantsByMonth(tenantIds, dateFrom, dateTo, ownerType, true);
        if (percentageByTenantAndMonth.isEmpty()) {
            // No company has any share this range — same "missing = 0%" rule as applyTrendEarnings().
            for (DashboardTrendPointDTO point : points) {
                point.setEarnings(BigDecimal.ZERO);
            }
            return;
        }

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> winLossByTenantDateRole = toTenantDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndTenantAndDate(tenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> crDrByTenantDateRole = toTenantDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndTenantAndDate(tenantIds, dateFrom, dateTo, roles, currency));

        for (DashboardTrendPointDTO point : points) {
            YearMonth month = YearMonth.from(point.getDate());
            BigDecimal dayEarnings = BigDecimal.ZERO;
            for (Map.Entry<Integer, Map<YearMonth, BigDecimal>> entry : percentageByTenantAndMonth.entrySet()) {
                BigDecimal percentage = entry.getValue().get(month);
                if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                Integer tenantId = entry.getKey();
                Map<String, BigDecimal> winLossByRole = winLossByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(point.getDate(), Map.of());
                Map<String, BigDecimal> crDrByRole = crDrByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(point.getDate(), Map.of());
                BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                BigDecimal companyNetProfit = companyProfit.add(companyExpenses);
                dayEarnings = dayEarnings.add(earningsFrom(companyNetProfit, percentage));
            }
            point.setEarnings(dayEarnings);
        }
    }

    /*
     * Group equity % per month — reuses resolveCompanyGroupAllocationsForTenantsByMonth
     * instead of a dedicated query, then filters to this groupTenantId in Java (equivalent
     * since a company allocates to at most one Group).
     */
    private Map<YearMonth, Map<Integer, BigDecimal>> resolveGroupEquityPercentagesByMonth(
            List<Integer> companyTenantIds, Integer groupTenantId, LocalDate dateFrom, LocalDate dateTo) {
        Map<Integer, Map<YearMonth, GroupAllocation>> allocationByTenant =
                resolveCompanyGroupAllocationsForTenantsByMonth(companyTenantIds, dateFrom, dateTo);

        Map<YearMonth, Map<Integer, BigDecimal>> percentageByMonth = new HashMap<>();
        for (Map.Entry<Integer, Map<YearMonth, GroupAllocation>> tenantEntry : allocationByTenant.entrySet()) {
            Integer tenantId = tenantEntry.getKey();
            for (Map.Entry<YearMonth, GroupAllocation> monthEntry : tenantEntry.getValue().entrySet()) {
                GroupAllocation allocation = monthEntry.getValue();
                if (!groupTenantId.equals(allocation.groupTenantId)) {
                    continue;
                }
                percentageByMonth.computeIfAbsent(monthEntry.getKey(), m -> new HashMap<>())
                        .put(tenantId, allocation.percentage);
            }
        }
        return percentageByMonth;
    }

    // A missing month counts as 0% (earnings=0, not null).
    private static void applyTrendEarnings(List<DashboardTrendPointDTO> points, Map<YearMonth, BigDecimal> percentageByMonth) {
        for (DashboardTrendPointDTO point : points) {
            BigDecimal percentage = percentageByMonth.getOrDefault(YearMonth.from(point.getDate()), BigDecimal.ZERO);
            point.setEarnings(earningsFrom(point.getNetProfit(), percentage));
        }
    }

    /** One point per day; a day missing from both maps still comes back as zero. */
    private static List<DashboardTrendPointDTO> buildTrendPoints(LocalDate dateFrom, LocalDate dateTo,
            Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole, Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole) {
        List<DashboardTrendPointDTO> points = new ArrayList<>();
        for (LocalDate date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            Map<String, BigDecimal> winLossByRole = winLossByDateRole.getOrDefault(date, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByDateRole.getOrDefault(date, Map.of());

            BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal netProfit = profit.add(expenses);

            points.add(new DashboardTrendPointDTO(date, profit, expenses, netProfit, null));
        }
        return points;
    }

    private static Map<LocalDate, Map<String, BigDecimal>> toDateRoleMap(List<DashboardTrendPointDTO.RoleAmount> rows) {
        Map<LocalDate, Map<String, BigDecimal>> byDate = new HashMap<>();
        for (DashboardTrendPointDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byDate.computeIfAbsent(row.getDate(), d -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byDate;
    }

    private static Map<Integer, Map<String, BigDecimal>> toTenantRoleMap(List<DashboardKpiDTO.RoleAmount> rows) {
        Map<Integer, Map<String, BigDecimal>> byTenant = new HashMap<>();
        for (DashboardKpiDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byTenant.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byTenant;
    }

    /*
     * Previous period: N whole calendar months -> previous N whole months (N=12 = last year);
     * anything else -> same day-count window immediately before.
     */
    private static LocalDate[] resolvePreviousRange(LocalDate dateFrom, LocalDate dateTo) {
        boolean isWholeCalendarMonths = dateFrom.getDayOfMonth() == 1
                && dateTo.equals(dateTo.withDayOfMonth(dateTo.lengthOfMonth()));
        if (isWholeCalendarMonths) {
            long monthSpan = ChronoUnit.MONTHS.between(
                    YearMonth.from(dateFrom).atDay(1), YearMonth.from(dateTo).atDay(1)) + 1;
            LocalDate previousDateTo = dateFrom.minusDays(1);
            LocalDate previousDateFrom = YearMonth.from(previousDateTo).minusMonths(monthSpan - 1).atDay(1);
            return new LocalDate[]{previousDateFrom, previousDateTo};
        }

        long lengthInDays = ChronoUnit.DAYS.between(dateFrom, dateTo) + 1;
        LocalDate previousDateTo = dateFrom.minusDays(1);
        LocalDate previousDateFrom = previousDateTo.minusDays(lengthInDays - 1);
        return new LocalDate[]{previousDateFrom, previousDateTo};
    }

    private ProfitExpenses computeProfitExpenses(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo,
                                                  String currency) {
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, BigDecimal> winLossByRole = toRoleMap(
                dashboardDao.aggregateWinLossByRole(tenantIds, dateFrom, dateTo, roles, currency));
        Map<String, BigDecimal> crDrByRole = toRoleMap(
                dashboardDao.aggregateCrDrByRole(tenantIds, dateFrom, dateTo, roles, currency));

        BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
        BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
        // expenses is already signed negative, so add (not subtract) gives profit - |expenses|.
        BigDecimal netProfit = profit.add(expenses);
        return new ProfitExpenses(profit, expenses, netProfit);
    }

    /* Win/Loss + Cr/Dr for one role; missing from either query counts as 0. */
    private static BigDecimal amountForRole(String role, Map<String, BigDecimal> winLossByRole,
                                             Map<String, BigDecimal> crDrByRole) {
        BigDecimal winLoss = winLossByRole.getOrDefault(role, BigDecimal.ZERO);
        BigDecimal crDr = crDrByRole.getOrDefault(role, BigDecimal.ZERO);
        return winLoss.add(crDr);
    }

    private static Map<String, BigDecimal> toRoleMap(List<DashboardKpiDTO.RoleAmount> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> row.getRole() == null ? "" : row.getRole().trim().toUpperCase(),
                DashboardKpiDTO.RoleAmount::getAmount,
                BigDecimal::add));
    }

    /* Earnings reflects only the current login's own share (owner, or PARTNERSHIP admin). */
    private void applyEarnings(DashboardKpiDTO dto, Integer tenantId, LocalDate dateTo, BigDecimal netProfit,
                                boolean allowGroupCascade) {
        String ownerType = resolveOwnerType();
        if (ownerType == null) {
            dto.setShowEarnings(false);
            return;
        }

        BigDecimal percentage = resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, allowGroupCascade);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            dto.setShowEarnings(false);
            return;
        }

        dto.setShowEarnings(true);
        dto.setEarningsPercentage(percentage);
        dto.setEarnings(earningsFrom(netProfit, percentage));
    }

    /* Same as applyEarnings, for the previous period's netProfit. */
    private BigDecimal resolveEarningsAmount(Integer tenantId, LocalDate dateTo, BigDecimal netProfit,
                                              boolean allowGroupCascade) {
        String ownerType = resolveOwnerType();
        if (ownerType == null) {
            return null;
        }
        BigDecimal percentage = resolveEffectiveEarningsPercentage(tenantId, dateTo, ownerType, allowGroupCascade);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return earningsFrom(netProfit, percentage);
    }

    /*
     * Earnings % for one tenant: 1) direct ownership wins if >0; 2) else, if
     * allowGroupCascade, fall back to (company's % into its Group) × (login's % in that
     * Group) / 100 — e.g. 10% into Group AP × login's 70% of AP = 7% effective share. False
     * for a Group's own Earnings (never cascades further). Thin wrapper over
     * resolveEffectiveEarningsPercentagesForTenants.
     */
    private BigDecimal resolveEffectiveEarningsPercentage(Integer tenantId, LocalDate dateTo, String ownerType,
                                                           boolean allowGroupCascade) {
        return resolveEffectiveEarningsPercentagesForTenants(List.of(tenantId), dateTo, ownerType, allowGroupCascade)
                .get(tenantId);
    }

    /* Batched resolveEffectiveEarningsPercentage — same direct-then-cascade rule per tenant,
     * just batched (§9). Returns only tenants with a percentage > 0; absent = 0. Thin
     * wrapper over resolveEffectiveEarningsPercentagesForTenantsByMonth. */
    private Map<Integer, BigDecimal> resolveEffectiveEarningsPercentagesForTenants(
            List<Integer> tenantIds, LocalDate dateTo, String ownerType, boolean allowGroupCascade) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        YearMonth month = YearMonth.from(dateTo);
        Map<Integer, Map<YearMonth, BigDecimal>> byMonth = resolveEffectiveEarningsPercentagesForTenantsByMonth(
                tenantIds, dateTo, dateTo, ownerType, allowGroupCascade);

        Map<Integer, BigDecimal> result = new HashMap<>();
        for (Map.Entry<Integer, Map<YearMonth, BigDecimal>> entry : byMonth.entrySet()) {
            BigDecimal percentage = entry.getValue().get(month);
            if (percentage != null) {
                result.put(entry.getKey(), percentage);
            }
        }
        return result;
    }

    /* This company's own "equity allocated to a Group" row — at most one Group per company. */
    private static final class GroupAllocation {
        private final Integer groupTenantId;
        private final BigDecimal percentage;

        private GroupAllocation(Integer groupTenantId, BigDecimal percentage) {
            this.groupTenantId = groupTenantId;
            this.percentage = percentage;
        }
    }

    /* "owner" / "user" (PARTNERSHIP admin) / null (member logins can't own shares). */
    private static String resolveOwnerType() {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            return null;
        }
        if ("owner".equalsIgnoreCase(session.user_type)) {
            return "owner";
        }
        if ("user".equalsIgnoreCase(session.user_type) && "partnership".equalsIgnoreCase(session.role)) {
            return "user";
        }
        return null;
    }

    /* Live table for the current month, monthly snapshot history table otherwise. */
    private static BigDecimal earningsFrom(BigDecimal netProfit, BigDecimal percentage) {
        return netProfit
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP);
    }

    private static final class ProfitExpenses {
        private final BigDecimal profit;
        private final BigDecimal expenses;
        private final BigDecimal netProfit;

        private ProfitExpenses(BigDecimal profit, BigDecimal expenses, BigDecimal netProfit) {
            this.profit = profit;
            this.expenses = expenses;
            this.netProfit = netProfit;
        }
    }

    private static void requireTenantId(Integer tenantId) {
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required");
        }
    }

    private static void requireGroupTenantId(Integer groupTenantId) {
        if (groupTenantId == null) {
            throw new BusinessException("group_tenant_id is required");
        }
    }

    private static void requireTenantIds(List<Integer> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new BusinessException("tenant_ids is required");
        }
    }

    private static void requireDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null || dateTo == null) {
            throw new BusinessException("date_from and date_to are required");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BusinessException("date_from must not be after date_to");
        }
    }

    private static String requireCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException("currency is required");
        }
        return currencyCode.trim();
    }

    private static String requireBaseCurrency(String baseCurrencyCode) {
        if (baseCurrencyCode == null || baseCurrencyCode.isBlank()) {
            throw new BusinessException("base_currency is required");
        }
        return baseCurrencyCode.trim().toUpperCase();
    }

    private static List<Integer> orEmpty(List<Integer> tenantIds) {
        return tenantIds == null ? List.of() : tenantIds;
    }

    private Tenant requireTenant(Integer tenantId) {
        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }
        return tenant;
    }

    private Tenant requireGroupTenant(Integer groupTenantId) {
        Tenant tenant = requireTenant(groupTenantId);
        if (tenant.getTenantType() != Tenant.TenantType.GROUP) {
            throw new BusinessException("Tenant is not a Group");
        }
        return tenant;
    }
}
