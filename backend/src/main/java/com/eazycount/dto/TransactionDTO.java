package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Transaction list search — Bank Process WIN/LOSE + Domain/Payment Cr/Dr.
 */
public final class TransactionDTO {

    private TransactionDTO() {
    }

    @Getter
    @Setter
    public static class SearchRequest {

        private Integer tenantId;

        /** Inclusive; dd/MM/yyyy or yyyy-MM-dd. */
        private String dateFrom;
        private String dateTo;

        /** Empty = all tenant currencies. */
        private List<String> currencyCodes;

        /** account.role filters; empty = all. */
        private List<String> categories;
    }

    @Getter
    @Setter
    public static class SearchRow {

        private Integer accountId;
        private String accountCode;
        private String accountName;
        private String role;
        private String currencyCode;
        private String bf;
        private String winLoss;
        private String crDr;
        private String balance;
        private boolean hasWinLossInPeriod;
        private boolean hasCrDrInPeriod;
    }

    @Getter
    @Setter
    public static class SearchTotals {

        private String bf;
        private String winLoss;
        private String crDr;
        private String balance;
    }

    @Getter
    @Setter
    public static class SearchResult {

        private List<SearchRow> rows = new ArrayList<>();
        private SearchTotals totals = new SearchTotals();
        private List<String> activeCurrencyCodes = new ArrayList<>();
    }

    /** MyBatis aggregate row — internal only. */
    @Getter
    @Setter
    public static class SearchAggregateRow {

        private Integer accountDbId;
        private String accountCode;
        private String accountName;
        private String role;
        private String currencyCode;
        private BigDecimal bfAmount;
        private BigDecimal winLossAmount;
        private BigDecimal crDrAmount;
        private Integer periodTxnCount;
    }

    @Getter
    @Setter
    public static class HistoryRequest {

        private Integer tenantId;

        /** account.id */
        private Integer accountId;

        /** Inclusive; dd/MM/yyyy or yyyy-MM-dd. */
        private String dateFrom;
        private String dateTo;

        /** Empty = all currencies for this account in range. */
        private List<String> currencyCodes;
    }

    @Getter
    @Setter
    public static class HistoryAccount {

        private Integer id;
        private String accountId;
        private String name;
    }

    @Getter
    @Setter
    public static class HistoryDateRange {

        private String from;
        private String to;
    }

    /** MyBatis BF aggregate — internal only. */
    @Getter
    @Setter
    public static class HistoryBfAggregateRow {

        private String currencyCode;
        private BigDecimal bfAmount;
    }

    /** MyBatis history line — internal only. */
    @Getter
    @Setter
    public static class HistoryLineRow {

        private Integer id;
        private String transactionType;
        private BigDecimal amount;
        private BigDecimal signedAmount;
        private java.time.LocalDate transactionDate;
        private String description;
        private String remark;
        private String createdBy;
        private java.time.LocalDateTime createdAt;
        private String currencyCode;
        private String cardOwner;
        private Boolean bankProcessLine;
        private Integer toAccountId;
        private Integer fromAccountId;
        private String toAccountCode;
        private String fromAccountCode;

        private String rateGroupId;
        private String rateExpression;
        private String rateCurrencyFromCode;
        private BigDecimal rateAmountFrom;
        private String rateCurrencyToCode;
        private Boolean rateMiddlemanFee;
        private String rateLeg1ToAccountCode;
        private BigDecimal rateMiddlemanRate;
        private String rateMiddlemanKind;
    }

    @Getter
    @Setter
    public static class HistoryRow {

        private Integer id;
        private String rowType;
        private String date;
        private Boolean isBankProcessTransaction;
        private String cardOwner;
        private String product;
        private String currency;
        private String rate;
        private String winLoss;
        private String crDr;
        private String balance;
        private String description;
        private String remark;
        private String createdBy;
    }

    @Getter
    @Setter
    public static class HistoryResult {

        private HistoryAccount account;
        private HistoryDateRange dateRange;
        private List<HistoryRow> history = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class SubmitRequest {

        private Integer tenantId;

        /** Defaults to PAYMENT when omitted. Supported: PAYMENT, CLAIM, CLEAR, CONTRA, ADJUSTMENT, PROFIT, RATE. */
        private String transactionType;

        /** Inclusive; dd/MM/yyyy or yyyy-MM-dd. Defaults to today when omitted. */
        private String transactionDate;

        /** To Account — payer (maps to {@code transactions.account_id}). */
        private Integer toAccountId;

        /** From Account — receiver (maps to {@code transactions.from_account_id}). */
        private Integer fromAccountId;

        /** Either {@code currencyId} or {@code currencyCode} is required. */
        private Integer currencyId;
        private String currencyCode;

        private BigDecimal amount;

        private String remark;

        // ── RATE (ignored for other types) ────────────────────────────────────

        /** Leg1 To (payer, first currency). */
        private Integer leg1ToAccountId;
        /** Leg1 From (receiver, first currency). */
        private Integer leg1FromAccountId;
        private Integer leg1CurrencyId;
        private String leg1CurrencyCode;
        private BigDecimal leg1Amount;

        /** Leg2 To (payer, second currency). */
        private Integer leg2ToAccountId;
        /** Leg2 From (receiver, second currency). */
        private Integer leg2FromAccountId;
        private Integer leg2CurrencyId;
        private String leg2CurrencyCode;
        private BigDecimal leg2Amount;

        private BigDecimal exchangeRate;

        private String rateExpression;
        private Integer middlemanAccountId;
        private BigDecimal middlemanRate;
        private BigDecimal middlemanAmount;
    }

    @Getter
    @Setter
    public static class SubmitResult {

        private Integer id;
        private String transactionType;
        private Integer tenantId;
        private Integer toAccountId;
        private Integer fromAccountId;
        private String currencyCode;
        private String amount;
        private String transactionDate;
        private String remark;

        /* RATE only. */
        private String rateGroupId;
        private Integer leg1Id;
        private Integer leg2Id;
        private Integer middlemanId;
        private Integer middlemanRateId;
        private Integer middlemanFeeId;
        private String exchangeRate;
        private String rateExpression;
    }
}
