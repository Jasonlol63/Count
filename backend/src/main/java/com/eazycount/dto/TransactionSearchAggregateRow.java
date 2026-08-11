package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TransactionSearchAggregateRow {

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
