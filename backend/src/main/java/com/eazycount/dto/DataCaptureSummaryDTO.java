package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/* Data Capture Summary formula API body — save / update / delete. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureSummaryDTO {

    private Integer id;
    private Integer tenantId;
    private Integer processId;

    private String processCode; // Bank code (SALARY/…) when numeric processId is not yet known.
    private String idProduct;

    private String productType;
    private String parentIdProduct;
    private Integer formulaVariant;
    private BigDecimal subOrder;
    private Integer rowIndex;

    private Integer accountId;
    private String accountDisplay;
    private Integer currencyId;
    private String currencyDisplay;

    private String description;
    private String sourceColumns;
    private String columnsDisplay;
    private String formula;
    private String inputMethod;
    private String sourcePercent;
    private Boolean enableSourcePercent;
    private Boolean enableInputMethod;

    /*Batch Delete Items Use */
    private List<DataCaptureSummaryDTO> items = new ArrayList<>();

    /* Batch delete response: how many rows were hard-deleted. */
    private Integer deletedCount;

    /* Batch delete response: deleted formula values. */
    private List<Integer> deletedIds = new ArrayList<>();
}
