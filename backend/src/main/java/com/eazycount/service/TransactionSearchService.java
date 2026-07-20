package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

public interface TransactionSearchService {

    TransactionDTO.SearchResult searchBankProcess(TransactionDTO.SearchRequest request);
}
