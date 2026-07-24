package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code transactions_deleted} — archive of soft-deleted transaction lines
 * for Payment Maintenance and Bank Process Maintenance (list still shows strikethrough rows).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDeleted {

    private Integer id;

    private Integer transactionId;

    private Integer tenantId;

    private Transaction.TransactionType transactionType;

    private Integer accountId;

    private Integer fromAccountId;

    private Integer currencyId;

    private BigDecimal amount;

    private LocalDate transactionDate;

    private String description;

    private String remark;

    private String createdBy;

    private LocalDateTime createdAt;

    private String deletedBy;

    private LocalDateTime deletedAt;

    private Integer bankProcessPostedId;

    private String rateGroupId;
}
