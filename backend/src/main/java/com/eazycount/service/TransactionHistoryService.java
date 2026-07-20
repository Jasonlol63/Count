package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

public interface TransactionHistoryService {

    TransactionDTO.HistoryResult historyBankProcess(TransactionDTO.HistoryRequest request);
}
