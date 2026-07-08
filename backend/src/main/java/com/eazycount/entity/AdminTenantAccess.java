package com.eazycount.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code user_tenant_access} table (staff/admin grants per tenant).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AdminTenantAccess {

    private Long id;

    private Integer userId;

    private Integer tenantId;

    private AclMode accountAclMode;

    private AclMode processAclMode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum AclMode {
        ALL,
        CUSTOM,
        NONE
    }
}
