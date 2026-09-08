package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dashboard KPI cards for one company (tenant_type=COMPANY) over a date range, plus the same
 * numbers for the aligned "previous period" (see {@link com.eazycount.service.impl.DashboardServiceImpl}
 * for how the previous range is picked). Percentage-change / delta formatting is a frontend
 * concern — this DTO only ever hands back raw amounts for both periods.
 * Group-level rollup and per-currency breakdown are not implemented yet — for a
 * GROUP tenant every amount field comes back null ("-" on the frontend).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiDTO {

    /** Sum of Win/Loss + Cr/Dr (CLEAR excluded) for account.role='PROFIT'. Null = unsupported (GROUP tenant). */
    private BigDecimal profit;

    /** Sum of Win/Loss + Cr/Dr (CLEAR excluded) for account.role='EXPENSES'. Null = unsupported (GROUP tenant). */
    private BigDecimal expenses;

    /** profit - expenses (missing side treated as 0). Null = unsupported (GROUP tenant). */
    private BigDecimal netProfit;

    /** Whether the 4th KPI card (Earnings) should render for the current logged-in identity. */
    private boolean showEarnings;

    /** The ownership percentage the Earnings figure was multiplied by; null when showEarnings is false. */
    private BigDecimal earningsPercentage;

    /** netProfit * earningsPercentage/100; null when showEarnings is false. */
    private BigDecimal earnings;

    /** Start of the auto-aligned previous period used for the comparison figures below. */
    private LocalDate previousDateFrom;

    /** End of the auto-aligned previous period used for the comparison figures below. */
    private LocalDate previousDateTo;

    /** Same as {@link #profit} but for the previous period. */
    private BigDecimal previousProfit;

    /** Same as {@link #expenses} but for the previous period. */
    private BigDecimal previousExpenses;

    /** Same as {@link #netProfit} but for the previous period. */
    private BigDecimal previousNetProfit;

    /** Same as {@link #earnings} but for the previous period; null whenever {@link #showEarnings} is false. */
    private BigDecimal previousEarnings;
}
