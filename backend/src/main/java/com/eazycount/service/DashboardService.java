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
}
