package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * BANK Data Capture draft — save/get by {@code tenantId + processCode + currencyId}.
 * Cells only (no remark). PROFIT is never persisted.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureBankDTO {

    private Integer tenantId;

    /** Business code: SALARY / COMMISSION / BONUS (PROFIT rejected on save). */
    private String processCode;

    private Integer processId;

    private Integer currencyId;

    private List<Cell> cells;

    /**
     * Snapshot shape for frontend {@code restoreCaptureTable}
     * ({@code headers}, {@code rows}, {@code rowCount}, {@code colCount}).
     */
    private Map<String, Object> tableData;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        /** 0-based (A=0). */
        private Integer rowIndex;
        /** 1-based UI column number. */
        private Integer colIndex;
        private String cellValue;
    }
}
