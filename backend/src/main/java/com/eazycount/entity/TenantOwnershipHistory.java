package com.eazycount.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TenantOwnershipHistory {
    private Integer id;
    private Integer tenantId;
    private LocalDate effectiveMonth;
    private Integer accountId;
    private String ownerType;
    private Integer partnerTenantId;
    private BigDecimal percentage;
    private Integer readOnly;
    private Integer savedBy;
    private LocalDateTime savedAt;
}
