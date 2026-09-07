package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of {@code account.role} → summed amount, from either the Win/Loss
 * or Cr/Dr aggregate query in {@link com.eazycount.dao.DashboardDao}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiRoleAmount {
    private String role;
    private BigDecimal amount;
}
