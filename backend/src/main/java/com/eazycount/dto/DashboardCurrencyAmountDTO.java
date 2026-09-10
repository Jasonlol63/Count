package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of the dashboard's Currency/Earning-tab breakdown table.
 * originalAmount: Net Profit computed in `code` itself (Profit + Expenses, that currency only).
 * amount: originalAmount converted into the caller's display/base currency.
 * rate: unit rate — 1 `code` expressed in the base currency (same conversion, amount=1).
 * amount/rate are null when no exchange rate is available yet for `code` or the base
 * currency — the frontend renders that as "—" rather than blocking the whole row.
 * earnings: the current login's ownership-weighted share of `code`'s Net Profit (0, never
 * null, when this currency had no activity or the login has no ownership stake — the
 * Earning tab shows 0 for those, unlike the Currency tab's "—").
 * earningsConverted: earnings converted into the display/base currency; null only when no
 * exchange rate is available (same rule as `amount`).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardCurrencyAmountDTO {
    private String code;
    private BigDecimal originalAmount;
    private BigDecimal amount;
    private BigDecimal rate;
    private BigDecimal earnings;
    private BigDecimal earningsConverted;
}
