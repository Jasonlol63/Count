package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Dashboard KPI cards for one company, current + auto-aligned previous period. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiDTO {

    // Win/Loss + Cr/Dr for role='PROFIT', role='EXPENSES'. Null for GROUP tenants.
    private BigDecimal profit;
    private BigDecimal expenses;
    private BigDecimal netProfit;      // profit + expenses = net profit

    private boolean showEarnings;            // Whether the Earnings card should render for the current identity.
    private BigDecimal earningsPercentage;   // Ownership % Earnings was multiplied by; null if showEarnings is false.
    private BigDecimal earnings;             // netProfit * earningsPercentage/100 = earning

    private LocalDate previousDateFrom;
    private LocalDate previousDateTo;
    private BigDecimal previousProfit;
    private BigDecimal previousExpenses;
    private BigDecimal previousNetProfit;
    private BigDecimal previousEarnings;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleAmount {
        private String role;
        private BigDecimal amount;
    }
}
