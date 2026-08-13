package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Customer Report: request filters (Controller#list) + one account row, or the synthesized Total row. */
@Getter
@Setter
public class CustomerReportDTO {

    // Request
    private Integer tenantId;
    /* Currency codes to filter to (e.g. ["MYR","SGD"]); null/empty = every currency the account is assigned (account_currency). */
    private List<String> currencyCodes;
    private String dateFrom;
    private String dateTo;
    private Integer accountId;
    private Boolean showAll;

    // Response row
    private Integer accountRowId;
    private String accountCode;
    private String accountName;
    private String currencyCode;
    private BigDecimal winAmount;
    private BigDecimal loseAmount;

    /* true only on the synthesized grand-total row appended at the end of the list. */
    private Boolean totalRow;
}
