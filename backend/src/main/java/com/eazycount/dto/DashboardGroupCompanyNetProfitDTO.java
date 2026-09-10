package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of the Group-only dashboard's "Net Profit" tab: one member company's own Net
 * Profit (not weighted by its equity % into the Group — that weighting only applies to the
 * Group's own aggregated Profit figure, see DashboardServiceImpl#computeGroupProfit), in
 * whatever single currency the caller requested. No FX conversion — same single-currency
 * filter as the Group KPI card itself.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardGroupCompanyNetProfitDTO {
    private String code;
    private BigDecimal netProfit;
    private String group;
}
