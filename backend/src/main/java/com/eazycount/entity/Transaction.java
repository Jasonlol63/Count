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
 * Maps to {@code transactions} — one tenant transaction line (one account amount).
 * Bank Process Post may create N lines that share the same {@link #bankProcessPostedId}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    private Integer id;

    private Integer tenantId;

    private TransactionType transactionType;

    private Integer accountId;

    private Integer fromAccountId;

    private Integer currencyId;

    private BigDecimal amount;

    private LocalDate transactionDate;

    private String description;

    private String remark;

    private String createdBy;

    private String updatedBy;

    private ApprovalStatus approvalStatus;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private Integer bankProcessPostedId;

    private String rateGroupId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum TransactionType {
        WIN,
        LOSE,
        PAYMENT,
        CONTRA,
        CLAIM,
        RATE,
        CLEAR,
        ADJUSTMENT,
        PROFIT
    }

    @Getter
    public enum ApprovalStatus {
        APPROVED,
        PENDING
    }
}
