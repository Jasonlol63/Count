package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code bank_process_accounting_posted} — Accounting Due ledger for a {@link BankProcess}.
 * Records which billing period was posted, skipped, or dismissed so Due inbox can exclude settled periods.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BkProcessAccountingPosted {

    private Integer id;

    private Integer tenantId;

    private Integer bankProcessId;

    private LocalDate postedDate;

    private PeriodType periodType;

    private Outcome outcome;

    private LocalDate billingStart;

    private LocalDate billingEnd;

    private LocalDateTime createdAt;

    private String createdBy;

    @Getter
    public enum PeriodType {
        MONTHLY,
        FIRST_MONTH,
        PARTIAL_FIRST_MONTH,
        FULL_MONTH,
        DAY_END_TAIL,
        ONCE_ONE_OFF,
        MANUAL_INACTIVE,
        RESEND_CONSOLIDATED,
        WEEKLY,
        DAILY,
        DAILY_CONSOLIDATED
    }

    @Getter
    public enum Outcome {
        POSTED,
        SKIPPED,
        DISMISSED
    }
}
