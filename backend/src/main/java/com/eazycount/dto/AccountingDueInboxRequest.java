package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Inbox request: numeric {@code tenantId} only, or {@code { tenantId, asOf }} for dev/testing.
 * {@code asOf} overrides {@code LocalDate.now()} when computing which month is due.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountingDueInboxRequest {

    private Integer tenantId;

    /** Optional; e.g. {@code 2026-08-01} to simulate August billing. */
    private LocalDate asOf;

    /** When true (Refresh button), restore user-skipped periods for the current month. */
    private Boolean restoreSkipped;
}
