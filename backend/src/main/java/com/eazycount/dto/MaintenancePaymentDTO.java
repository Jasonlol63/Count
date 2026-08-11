package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class MaintenancePaymentDTO {

    private Integer tenantId;

    private String dateFrom;
    private String dateTo;

    private String transactionType;

    private String q;

    private List<String> currencyCodes;
    private List<Integer> transactionIds;

    private Integer id;
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
}
