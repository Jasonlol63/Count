package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// One row of https://api.frankfurter.dev/v2/rates?base=...&quotes=... — that endpoint returns
// a JSON array of these (one per quote currency), not the v1 /latest {base,date,rates:{...}}
// map shape this DTO used to model.
@Getter
@Setter
public class FrankfurterRateRow {
    private String date;
    private String base;
    private String quote;
    private BigDecimal rate;
}
