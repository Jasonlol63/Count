package com.eazycount.service;

import com.eazycount.dto.AccountingDueDTO;

import java.time.LocalDate;
import java.util.List;

public interface AccountingDueService {

    List<AccountingDueDTO> resolveInbox(Integer tenantId, LocalDate asOf, boolean restoreSkipped);

    void skipPeriods(List<AccountingDueDTO> items);

    /**
     * Post selected Accounting Due periods to {@code transactions}.
     * Supported: {@code FIRST_OF_EVERY_MONTH}, {@code MONTHLY}, {@code WEEK}, {@code DAY}, {@code ONCE}.
     *
     * @return number of transaction lines created
     */
    int postToTransaction(List<AccountingDueDTO> items);
}
