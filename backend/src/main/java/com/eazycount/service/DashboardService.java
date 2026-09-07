package com.eazycount.service;

import com.eazycount.dto.DashboardKpiDTO;

import java.time.LocalDate;

public interface DashboardService {

    /**
     * Profit / Expenses / Net Profit / Earnings KPI cards for one tenant over [dateFrom, dateTo].
     * Earnings visibility and percentage are resolved for the currently logged-in identity
     * (owner, or an admin user with role=PARTNERSHIP) — never another account's share.
     */
    DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo);
}
