package com.eazycount.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code user_tenant_access} table (admin grants per tenant).
 * {@code capabilities} JSON e.g. GROUP_LEDGER_READ, SUBSIDIARY_WRITE (separate from login_scope).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserTenantAccess {

    private Long id;

    private Integer userId;

    private Integer tenantId;

    /** JSON array of capability codes */
    private String capabilities;

    /** JSON array; subsidiary account ACL when tenant is company */
    private String accountPermissions;

    /** JSON array; subsidiary process ACL when tenant is company */
    private String processPermissions;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
