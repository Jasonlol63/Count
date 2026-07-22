package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

/** Manual submit: PAYMENT/CLAIM/CLEAR/CONTRA (Cr/Dr transfer) and ADJUSTMENT (Win/Loss, To only). */
public interface TransactionSubmitService {

    TransactionDTO.SubmitResult submit(TransactionDTO.SubmitRequest request);
}
