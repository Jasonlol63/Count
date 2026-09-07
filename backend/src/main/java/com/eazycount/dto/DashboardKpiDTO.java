package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Dashboard KPI cards for one company (tenant_type=COMPANY) over a date range.
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
}
