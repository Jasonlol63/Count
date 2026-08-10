package com.eazycount.service;

import com.eazycount.dto.DataCaptureBankDTO;
import com.eazycount.dto.DataCaptureGameDTO;

import java.time.LocalDate;
import java.util.List;

public interface DataCaptureService {

    DataCaptureGameDTO loadGameCaptureForm(DataCaptureGameDTO request);

    List<DataCaptureGameDTO> findAllProcessSubmittedByIdAndDate(DataCaptureGameDTO request);

    /* Save BANK draft cells (SALARY/COMMISSION/BONUS only; PROFIT rejected). */
    DataCaptureBankDTO saveBankDraft(DataCaptureBankDTO request);

    /* Load BANK draft for tenant+processCode+currencyId (PROFIT always empty). */
    DataCaptureBankDTO getBankDraft(DataCaptureBankDTO request);
}
