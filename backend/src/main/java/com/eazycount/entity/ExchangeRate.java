package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRate {
    private Integer id;
    private String currencyCode;
    private BigDecimal rateToUsd;
    private LocalDate rateDate;
    private String source;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;

    public ExchangeRate(String currencyCode, BigDecimal rateToUsd, LocalDate rateDate, String source) {
        this.currencyCode = currencyCode;
        this.rateToUsd = rateToUsd;
        this.rateDate = rateDate;
        this.source = source;
    }
}
