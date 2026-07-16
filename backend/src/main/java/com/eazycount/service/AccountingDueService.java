package com.eazycount.service;

import com.eazycount.dto.AccountingDueDTO;

import java.time.LocalDate;
import java.util.List;

public interface AccountingDueService {

    List<AccountingDueDTO> resolveInbox(Integer tenantId, LocalDate asOf, boolean restoreSkipped);

    void skipPeriods(List<AccountingDueDTO> items);
}
