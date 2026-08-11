package com.eazycount.service;

import com.eazycount.dto.TransactionSubmitDTO;

/** Manual submit: PAYMENT/CLAIM/CLEAR/CONTRA (Cr/Dr), ADJUSTMENT (Win/Loss To-only), PROFIT (Win/Loss From+To), RATE (Cr/Dr two legs + optional Middle-Man fee Win/Loss + FX header). */
public interface TransactionSubmitService {

    TransactionSubmitDTO submit(TransactionSubmitDTO request);
}
