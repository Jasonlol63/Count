package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to {@code transactions_rate} — one RATE group header per submit.
 * Ledger amounts live on {@link Transaction} legs; this row stores FX metadata + leg links.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRate {

    private Integer id;

    private Integer tenantId;

    private String rateGroupId;

    private Integer leg1TransactionId;

    private Integer leg2TransactionId;

    private BigDecimal exchangeRate;

    private String rateExpression;

    private Integer currencyFromId;

    private BigDecimal amountFrom;

    private Integer currencyToId;

    private BigDecimal amountTo;

    private Integer middlemanAccountId;

    private BigDecimal middlemanRate;

    /** Raw Rate-Mul input, e.g. {@code /1.55} or {@code 2.93}; drives divide vs multiply mode. */
    private String middlemanRateExpression;

    /** Service Fee face value, currency_to; no FX conversion. */
    private BigDecimal middlemanAmount;

    /** Platform Fee face value, currency_to; reduces middleman profit (Fee - PT), no separate ledger row. */
    private BigDecimal platformFeeAmount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
