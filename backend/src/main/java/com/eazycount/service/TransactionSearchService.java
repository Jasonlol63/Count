package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

public interface TransactionSearchService {

    /**
     * Transaction search grid: Bank Process (Win/Loss) + Domain Payment (Cr/Dr).
     */
    TransactionDTO.SearchResult searchList(TransactionDTO.SearchRequest request);
}
