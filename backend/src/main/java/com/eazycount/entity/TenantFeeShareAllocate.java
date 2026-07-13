package com.eazycount.entity;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to {@code tenant_fee_share_allocation} — one row per fee-share recipient
 * (replaces {@code tenant.fee_share_allocations} JSON).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TenantFeeShareAllocate {

    private Integer id;

    private Integer tenantId;

    private ShareType shareType;

    private Integer accountId;

    private String ownerType;

    private Integer partnerTenantId;

    private BigDecimal percentage;

    private Integer sortOrder;

    @Getter
    public enum ShareType {
        SALES,
        CS,
        IT,
        PROFIT
    }
}
