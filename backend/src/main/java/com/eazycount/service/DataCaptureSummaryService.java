package com.eazycount.service;

import com.eazycount.dto.DataCaptureSummaryDTO;
import com.eazycount.dto.DataCaptureSummarySubmitDTO;

public interface DataCaptureSummaryService {

    DataCaptureSummaryDTO saveAddFormula(DataCaptureSummaryDTO request);

    DataCaptureSummaryDTO updateFormula(DataCaptureSummaryDTO request);

    DataCaptureSummaryDTO deleteFormulas(DataCaptureSummaryDTO request);

    DataCaptureSummarySubmitDTO submit(DataCaptureSummarySubmitDTO request);
}
