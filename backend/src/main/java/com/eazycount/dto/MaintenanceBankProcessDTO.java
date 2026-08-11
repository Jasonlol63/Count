package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class MaintenanceBankProcessDTO {

    private Integer tenantId;

    private String dateFrom;
    private String dateTo;
    private String q;

    private List<String> currencyCodes;
    private List<Integer> transactionIds;

    private Integer id;
    private String transactionType;
    private LocalDateTime createdAt;
    private String toAccountCode;
    private String fromAccountCode;
    private BigDecimal amount;
    private String currencyCode;
    private String description;
    private String remark;
    private String createdBy;

    private Boolean deleted;
    private String deletedBy;
    private LocalDateTime deletedAt;

    private Integer bankProcessId;

    private String periodType;

    private LocalDate transactionDate;
}
