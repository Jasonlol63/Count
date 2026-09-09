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

    /** 当天的 Earnings：当前登录身份按"这一天所在月份"的股权% 算出来的。身份本身不具备
     *  股权资格（member/账本科目登录）时整条线都是 null；身份具备资格但某个月没配置过股权，
     *  按 0% 处理（数字是 0，不是 null）。 */
    private BigDecimal earnings;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleAmount {
        private LocalDate date;
        private String role;
        private BigDecimal amount;
        private Integer tenantId;
    }
}
