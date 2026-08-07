package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureLineDTO {

    private Integer id;
    private Integer captureId;

    private String productType; // MAIN / SUB
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

    /* Client-computed final amount (6dp plain string) — server re-truncates, never trusted as-is. */
    private String processedAmount;
    /* Raw rate expression e.g. "*3" / "/3" / "3"; null when the row has no rate. */
    private String rateValue;
}
