package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

/** Manual submit: PAYMENT/CLAIM/CLEAR/CONTRA (Cr/Dr), ADJUSTMENT (Win/Loss To-only), PROFIT (Win/Loss From+To), RATE (Cr/Dr two legs + optional Middle-Man fee Win/Loss + FX header). */
public interface TransactionSubmitService {

    TransactionDTO.SubmitResult submit(TransactionDTO.SubmitRequest request);
}
