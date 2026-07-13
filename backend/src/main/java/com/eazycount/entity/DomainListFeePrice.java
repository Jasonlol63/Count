package com.eazycount.entity;

import com.eazycount.entity.Tenant.TenantType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to {@code domain_list_fee_price} — one row per tenant type and renewal period.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DomainListFeePrice {

    private TenantType tenantType;

    private String period;

    private BigDecimal price;

    private LocalDateTime updatedAt;
}
