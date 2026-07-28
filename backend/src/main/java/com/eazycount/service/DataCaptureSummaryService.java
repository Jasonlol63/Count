package com.eazycount.service;

import com.eazycount.dto.DataCaptureSummaryDTO;

public interface DataCaptureSummaryService {

    DataCaptureSummaryDTO saveAddFormula(DataCaptureSummaryDTO request);

    DataCaptureSummaryDTO updateFormula(DataCaptureSummaryDTO request);

    /**
     * Hard-delete formula rows by id or business key ({@code request.items}).
     * No subOrder resequence. Response uses {@code deletedCount} / {@code deletedIds}.
     */
    DataCaptureSummaryDTO deleteFormulas(DataCaptureSummaryDTO request);
}
