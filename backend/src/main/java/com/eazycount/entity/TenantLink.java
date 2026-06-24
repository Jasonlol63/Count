package com.eazycount.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code tenant_link} table (peer links between group tenants, e.g. AP ↔ IG).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TenantLink {

    private Integer id;

    private Integer tenantId;

    private Integer linkedTenantId;

    private String linkType;

    private LocalDateTime createdAt;
}
