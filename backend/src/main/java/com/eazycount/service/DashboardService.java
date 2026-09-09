package com.eazycount.service;

import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;

import java.time.LocalDate;
import java.util.List;

public interface DashboardService {

    // KPI cards for one tenant/currency over [dateFrom, dateTo], plus the previous period.
    DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    //Trend Chart use, same rules as getKpi, one point per day in [dateFrom, dateTo].
    List<DashboardTrendPointDTO> getTrend(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // "Company: All" rollup — Profit/Expenses/NetProfit summed across every given tenant, one currency. No Earnings, no previous-period. Caller resolves which tenantIds are in scope.
    DashboardKpiDTO getKpiForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

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
}
