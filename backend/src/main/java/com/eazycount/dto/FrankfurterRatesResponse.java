package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

// Shape of https://api.frankfurter.dev/v2/latest?base=USD&symbols=... — only the fields we use.
@Getter
@Setter
public class FrankfurterRatesResponse {
    private String base;
    private String date;
    private Map<String, BigDecimal> rates;
}
