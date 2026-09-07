package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/* Data Capture draft — shared shape for BANK (save/get by "tenantId + processCode + currencyId",
   PROFIT never persisted) and GAME (save/get by "tenantId + processId + currencyId", gated by
   Process.enableSaveDraft instead of a code whitelist). Cells only (no remark). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureBankDTO {

    private Integer tenantId;

    /* Business code: SALARY / COMMISSION / BONUS (PROFIT rejected on save). */
    private String processCode;

    private Integer processId;

    private Integer currencyId;

    private List<Cell> cells;

    /* Snapshot shape for frontend "restoreCaptureTable" */
    private Map<String, Object> tableData;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        /* 0-based (A=0). */
        private Integer rowIndex;
        /* 1-based UI column number. */
        private Integer colIndex;
        private String cellValue;
    }
}
