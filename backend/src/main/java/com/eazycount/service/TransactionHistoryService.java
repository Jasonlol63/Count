package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

public interface TransactionHistoryService {

    /**
     * Payment History list for one account: Bank Process (Win/Loss) + Domain Payment (Cr/Dr).
     */
    TransactionDTO.HistoryResult historyList(TransactionDTO.HistoryRequest request);
}
