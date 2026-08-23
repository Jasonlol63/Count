package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureFormula {

    private Integer id;
    private Integer tenantId;
    private Integer processId;
    private String idProduct;
    private ProductType productType;
    private String parentIdProduct;

    private Integer formulaVariant;
    private BigDecimal subOrder;
    private Integer rowIndex;

    private Integer accountId;
    private Integer currencyId;
    private String description;

    private String sourceColumns;
    private String columnsDisplay;

    private String formula;
    private String inputMethod;
    private String sourcePercent;
    private Boolean enableSourcePercent;
    private Boolean enableInputMethod;

    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ProductType {
        MAIN,
        SUB
    }
}
