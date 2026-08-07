package com.eazycount.service;

import com.eazycount.dto.DataCaptureSummaryDTO;

public interface DataCaptureSummaryService {

    DataCaptureSummaryDTO saveAddFormula(DataCaptureSummaryDTO request);

    DataCaptureSummaryDTO updateFormula(DataCaptureSummaryDTO request);

    DataCaptureSummaryDTO deleteFormulas(DataCaptureSummaryDTO request);
}
