package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * API view of domain list fee settings (GROUP + COMPANY period prices).
 * Built from {@code domain_list_fee_price} rows for backward-compatible JSON.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DomainFeeSettingsDTO {

    @JsonProperty("company_period_prices")
    private PeriodPrices companyPeriodPrices;

    @JsonProperty("group_period_prices")
    private PeriodPrices groupPeriodPrices;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodPrices {

        @JsonProperty("7days")
        private BigDecimal days7;

        @JsonProperty("1month")
        private BigDecimal month1;

        @JsonProperty("3months")
        private BigDecimal months3;

        @JsonProperty("6months")
        private BigDecimal months6;

        @JsonProperty("1year")
        private BigDecimal year1;
    }
}
