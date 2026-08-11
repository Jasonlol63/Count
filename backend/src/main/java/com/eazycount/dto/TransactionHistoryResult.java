package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class TransactionHistoryResult {

    private Account account;
    private DateRange dateRange;
    private List<Row> history = new ArrayList<>();

    @Getter
    @Setter
    public static class Account {

        private Integer id;
        private String accountId;
        private String name;
    }

    @Getter
    @Setter
    public static class DateRange {

        private String from;
        private String to;
    }

    @Getter
    @Setter
    public static class Row {

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
}
