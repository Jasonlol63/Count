package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TransactionHistoryLineRow {

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
    /* True only for "findDataCaptureHistoryLines" rows — drives the "DATA CAPTURE" ID Product label. */
    private Boolean dataCaptureLine;
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
