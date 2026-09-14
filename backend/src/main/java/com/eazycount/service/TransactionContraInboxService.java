package com.eazycount.service;

import com.eazycount.dto.TransactionContraInboxDTO;

import java.util.List;

/** Contra Inbox: list/approve/reject PENDING manual transactions (Owner/Admin/Manager only). */
public interface TransactionContraInboxService {

    List<TransactionContraInboxDTO> listPending(Integer tenantId);

    void approve(TransactionContraInboxDTO request);

    void reject(TransactionContraInboxDTO request);
}
