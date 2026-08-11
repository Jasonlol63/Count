package com.eazycount.service;

import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;

public interface TransactionHistoryService {

    /**
     * Payment History list for one account: Bank Process (Win/Loss) + Domain Payment (Cr/Dr).
     */
    TransactionHistoryResult historyList(TransactionHistoryRequest request);
}
