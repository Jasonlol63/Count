package com.eazycount.service;

import com.eazycount.dto.DataCaptureBankDTO;
import com.eazycount.dto.DataCaptureGameDTO;

public interface DataCaptureService {

    DataCaptureGameDTO loadGameCaptureForm(DataCaptureGameDTO request);

    /* Save BANK draft cells (SALARY/COMMISSION/BONUS only; PROFIT rejected). */
    DataCaptureBankDTO saveBankDraft(DataCaptureBankDTO request);

    /* Load BANK draft for tenant+processCode+currencyId (PROFIT always empty). */
    DataCaptureBankDTO getBankDraft(DataCaptureBankDTO request);
}
