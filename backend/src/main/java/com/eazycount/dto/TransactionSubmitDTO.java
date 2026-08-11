package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/*
 * Manual "Add Transaction" submit — single DTO for request and result.
 * PAYMENT/CLAIM/CLEAR/CONTRA (Cr/Dr), ADJUSTMENT (Win/Loss To-only), PROFIT (Win/Loss From+To),
 * RATE (Cr/Dr two legs + optional Middle-Man fee Win/Loss + FX header).
 * Backs "TransactionController#submit".
 */
@Getter
@Setter
public class TransactionSubmitDTO {

    // ── Request ──────────────────────────────────────────────────────────────
    private Integer tenantId;

    /* Defaults to PAYMENT when omitted. Supported: PAYMENT, CLAIM, CLEAR, CONTRA, ADJUSTMENT, PROFIT, RATE. */
    private String transactionType;
    private String transactionDate;

    /* To Account — payer (maps to "transactions.account_id"). From Account — receiver maps to "transactions.from_account_id".*/
    private Integer toAccountId;
    private Integer fromAccountId;

    private Integer currencyId;
    private String currencyCode;
    private BigDecimal amount;
    private String remark;

    // ── RATE (ignored for other types) ────────────────────────────────────
    /* Leg1 To (payer, first currency). Leg1 From (receiver, first currency). */
    private Integer leg1ToAccountId;
    private Integer leg1FromAccountId;
    private Integer leg1CurrencyId;
    private String leg1CurrencyCode;
    private BigDecimal leg1Amount;

    /* Leg2 To (payer, second currency). Leg2 From (receiver, second currency).*/
    private Integer leg2ToAccountId;
    private Integer leg2FromAccountId;
    private Integer leg2CurrencyId;
    private String leg2CurrencyCode;
    private BigDecimal leg2Amount;

    /* Request: raw submitted rate. */
    private BigDecimal exchangeRate;
    private String rateExpression;
    private Integer middlemanAccountId;
    private BigDecimal middlemanRate;
    private BigDecimal middlemanAmount;

    // ── Result-only ──────────────────────────────────────────────────────────
    private Integer id;

    private String amountDisplay;
    private String exchangeRateDisplay;

    /* RATE only. */
    private String rateGroupId;
    private Integer leg1Id;
    private Integer leg2Id;
    private Integer middlemanId;
    private Integer middlemanRateId;
    private Integer middlemanFeeId;
}
