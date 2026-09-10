package com.eazycount.service;

import com.eazycount.dto.DashboardCurrencyAmountDTO;
import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;

import java.time.LocalDate;
import java.util.List;

public interface DashboardService {

    // KPI cards for one tenant/currency over [dateFrom, dateTo], plus the previous period.
    DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Currency-tab breakdown: this tenant's Net Profit broken out per currency it actually has
    // transactions in for [dateFrom, dateTo] (one batched query, not one call per currency),
    // each row converted into baseCurrencyCode via ExchangeRateService. A currency with no rate
    // yet still returns its originalAmount, with amount/rate left null.
    List<DashboardCurrencyAmountDTO> getKpiCurrencyBreakdown(Integer tenantId, LocalDate dateFrom, LocalDate dateTo,
                                                              String baseCurrencyCode);

    //Trend Chart use, same rules as getKpi, one point per day in [dateFrom, dateTo].
    List<DashboardTrendPointDTO> getTrend(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // "Company: All" rollup — Profit/Expenses/NetProfit summed across every given tenant, one currency. No Earnings, no previous-period. Caller resolves which tenantIds are in scope.
    DashboardKpiDTO getKpiForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // "Company: All" Currency tab — same shape as getKpiCurrencyBreakdown(), but originalAmount
    // is summed across every given tenant per currency (no equity weighting, unlike the Group
    // version) — same "just sum, no Group Cascade" rule as getKpiForCompanies(). No Earnings
    // (same §9.1 rule as getKpiForCompanies()) — earnings/earningsConverted always null.
    List<DashboardCurrencyAmountDTO> getKpiCurrencyBreakdownForCompanies(
            List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String baseCurrencyCode);

    //"Company: All" trend Chart use - Profit/Expenses/NetProfit summed across every given tenant, one currency.
    List<DashboardTrendPointDTO> getTrendForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group KPI cards — Group Profit = each member company's Net Profit × its equity % in
    // this Group (0% contributes 0). Group Expenses = the Group's own ledger. Net Profit =
    // Profit + Expenses. Earnings = Net Profit × the current login's own share in the Group.
    // companyTenantIds: member companies, resolved by the caller (frontend), same as getKpiForCompanies.
    DashboardKpiDTO getKpiForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                    LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group Trend Chart — same Group Profit/Expenses/NetProfit algorithm as getKpiForGroup,
    // just one point per day instead of one total for the whole range. The Earnings line
    // (same for Company mode's getTrend) looks up each day's own month's ownership % instead
    // of one flat percentage for the whole range; a month with no config counts as 0%.
    List<DashboardTrendPointDTO> getTrendForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                                   LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group-only "Net Profit" tab: each member company's own Net Profit (not weighted by its
    // equity % into the Group), in a single requested currency — no FX conversion. Separate,
    // on-demand endpoint (only called when that tab is actually visible) rather than folded
    // into getKpiForGroup(), so scopes that never need it don't pay for it.
    List<DashboardCurrencyAmountDTO.CompanyNetProfit> getGroupCompanyNetProfitBreakdown(
            Integer groupTenantId, List<Integer> companyTenantIds,
            LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group-only Currency tab: same shape/semantics as getKpiCurrencyBreakdown(), but the
    // per-currency originalAmount is this Group's own weighted Net Profit in that currency —
    // sum over member companies of (that company's Net Profit in this currency × its equity %),
    // plus the Group's own ledger Expenses in this currency. Earnings/earningsConverted are
    // left null for now (Group's per-currency Earning tab is a separate follow-up).
    List<DashboardCurrencyAmountDTO> getGroupKpiCurrencyBreakdown(
            Integer groupTenantId, List<Integer> companyTenantIds,
            LocalDate dateFrom, LocalDate dateTo, String baseCurrencyCode);
}
