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

    /** Transfer-style only; otherwise null. */
    private Integer fromAccountId;

    private Integer currencyId;

    private BigDecimal amount;

    /** Economic / capture date for list filters. */
    private LocalDate transactionDate;

    /** System / process line description. */
    private String description;

    /** User / system remark (Payment History Remark). */
    private String remark;

    /** Creator login_id (admin = user.login_id; owner = owner_code). */
    private String createdBy;

    /** Last updater login_id (same convention as createdBy). */
    private String updatedBy;

    private ApprovalStatus approvalStatus;

    /** Approver login_id (same convention as createdBy). */
    private String approvedBy;

    private LocalDateTime approvedAt;

    /**
     * FK {@code bank_process_accounting_posted.id}.
     * Null = manual / non–Bank Process transaction.
     */
    private Integer bankProcessPostedId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum TransactionType {
        WIN,
        LOSE,
        PAYMENT,
        RECEIVE,
        CONTRA,
        CLAIM,
        RATE,
        CLEAR,
        ADJUSTMENT
    }

    @Getter
    public enum ApprovalStatus {
        APPROVED,
        PENDING
    }
}
