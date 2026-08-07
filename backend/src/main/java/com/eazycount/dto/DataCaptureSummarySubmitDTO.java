package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureSummarySubmitDTO {

    private Integer tenantId;
    private String category; // GAME / BANK — resolved from process when omitted

    private Integer processId;
    private String processCode; // Bank code (SALARY/…) fallback when numeric processId is not yet known

    private LocalDate captureDate;
    private Integer currencyId;
    private String remark;
    private String removeWord;
    private String replaceWordFrom;
    private String replaceWordTo;

    private List<DataCaptureLineDTO> lines = new ArrayList<>();

    /* Response: generated data_captures.id. */
    private Integer captureId;
}
