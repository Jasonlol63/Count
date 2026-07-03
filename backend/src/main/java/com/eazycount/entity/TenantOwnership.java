package com.eazycount.entity;

import java.math.BigDecimal;

import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TenantOwnership {
    private Integer id;
    private Integer tenantId;
    private Integer accountId;
    private String ownerType;
    private Integer partnerTenantId;
    private BigDecimal percentage;
    private Integer readOnly;
    private Integer sortOrder;

}
