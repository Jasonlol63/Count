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

    private BigDecimal middlemanAmount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
