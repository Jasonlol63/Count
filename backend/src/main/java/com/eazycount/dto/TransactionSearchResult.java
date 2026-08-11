package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/* Transaction Payment main list response. Backs "TransactionController#search". */
@Getter
@Setter
public class TransactionSearchResult {

    private List<Row> rows = new ArrayList<>();
    private Totals totals = new Totals();
    private List<String> activeCurrencyCodes = new ArrayList<>();

    @Getter
    @Setter
    public static class Row {

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
        private boolean neverTransacted;
    }

    @Getter
    @Setter
    public static class Totals {

        private String bf;
        private String winLoss;
        private String crDr;
        private String balance;
    }
}
