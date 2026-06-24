package com.eazycount.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DomainFee {

    private Integer id;
    private PriceMap companyPrice;
    private PriceMap groupPrice;
    private LocalDateTime updatedTime;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceMap {
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
