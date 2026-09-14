package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Contra Inbox: pending manual transactions (approval_status = PENDING) awaiting an Owner/Admin/Manager
 * to approve or reject — see AccessControlUtils.requireContraInboxApprover and
 * TransactionSubmitServiceImpl.isAutoApproved for how a row becomes PENDING in the first place.
 * Dual-purpose DTO: {@code tenantId}/{@code id} carry the list/approve/reject request, the remaining
 * fields carry one row of {@code listPending}'s result (same shape convention as MaintenancePaymentDTO).
 */
@Getter
@Setter
public class TransactionContraInboxDTO {

    private Integer tenantId;

    /* approve / reject target. */
    private Integer id;

    // ── Result-only (one pending row) ──────────────────────────────────────
    private String transactionType;
    private LocalDateTime createdAt;
    private LocalDate transactionDate;
    private String toAccountCode;
    private String fromAccountCode;
    private BigDecimal amount;
    private String currencyCode;
    private String description;
    private String remark;
    private String createdBy;
}
