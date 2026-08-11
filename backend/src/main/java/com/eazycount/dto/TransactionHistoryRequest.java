package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/* Payment History per-account drill-down request. Backs "TransactionController#history". */
@Getter
@Setter
public class TransactionHistoryRequest {

    private Integer tenantId;

    private Integer accountId;

    private String dateFrom;
    private String dateTo;

    private List<String> currencyCodes;
}
