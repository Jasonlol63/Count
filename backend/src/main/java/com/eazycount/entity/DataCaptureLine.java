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
public class DataCaptureLine {

    private Integer id;

    private Integer tenantId;

    private Integer captureId;

    private ProductType productType;

    private String idProduct;
    private String idProductMain;
    private String idProductSub;

    private String descriptionMain;
    private String descriptionSub;

    private Integer formulaVariant;
    private Integer displayOrder;

    private Integer accountId;
    private Integer currencyId;

    private String sourceColumns;
    private String sourceValue;
    private String sourcePercent;
    private Boolean enableSourcePercent;

    private String formula;

    private BigDecimal processedAmount;
    private BigDecimal rate;
    private String rateExpression;

    private LocalDateTime createdAt;

    @Getter
    public enum ProductType {
        MAIN,
        SUB
    }
}
