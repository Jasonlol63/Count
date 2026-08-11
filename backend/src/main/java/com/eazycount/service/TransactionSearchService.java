package com.eazycount.service;

import com.eazycount.dto.TransactionSearchRequest;
import com.eazycount.dto.TransactionSearchResult;

public interface TransactionSearchService {

    /**
     * Transaction search grid: Bank Process (Win/Loss) + Domain Payment (Cr/Dr).
     */
    TransactionSearchResult searchList(TransactionSearchRequest request);
}
