package com.eazycount.service;

import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.BankProcessDTO;

import java.time.LocalDate;

public interface BankProcessResendService {

    AccountingDueDTO resend(AccountingDueDTO request);

    /** Open make-up row for inbox merge; null when none. Never filtered by today/asOf. */
    AccountingDueDTO resolveOpenMakeUp(BankProcessDTO dto, LocalDate today);

    void clearOpenSchedule(Integer bankProcessId, Integer tenantId);
}
