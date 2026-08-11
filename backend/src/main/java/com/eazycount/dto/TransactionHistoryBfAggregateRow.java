package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/* MyBatis BF aggregate for Payment History — internal only, not a controller DTO. */
@Getter
@Setter
public class TransactionHistoryBfAggregateRow {

    private String currencyCode;
    private BigDecimal bfAmount;
}
