package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code bank_process_resend_daily_guard} — same-day Resend lock for a {@link BankProcess}.
 * Written on Post success for a make-up {@code resendDayStart}; frequency-agnostic.
 * Cleared when Maintenance deletes that bank-process transaction, or when {@code guardDate} is no longer today.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BkProcessResend {

    private Integer id;

    private Integer tenantId;

    private Integer bankProcessId;

    private LocalDate resendDayStart;

    private LocalDate guardDate;

    private LocalDateTime createdAt;
}
