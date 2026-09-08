package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/* One Trend Chart data point — same Profit/Expenses/Net Profit math, per day. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardTrendPointDTO {
    private LocalDate date;
    private BigDecimal profit;
    private BigDecimal expenses;
    private BigDecimal netProfit;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleAmount {
        private LocalDate date;
        private String role;
        private BigDecimal amount;
    }
}
