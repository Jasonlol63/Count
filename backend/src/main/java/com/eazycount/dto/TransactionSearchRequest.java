package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/* Transaction Payment main list request. Backs "TransactionController#search". */
@Getter
@Setter
public class TransactionSearchRequest {

    private Integer tenantId;

    private String dateFrom;
    private String dateTo;

    /* Empty = all tenant currencies. */
    private List<String> currencyCodes;

    /* account.role filters; empty = all. */
    private List<String> categories;

    /*
     * When true, include account×currency shells with no approved transaction history
     * (never-transacted), so Show all 0 balance can union them on the client.
     */
    private Boolean showAllZeroBalance;
}
