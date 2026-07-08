package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code user_tenant_account_access} (account whitelist under one user-tenant grant).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AdminTenantAccountAccess {

    private Long id;

    /** FK {@code user_tenant_access.id}. */
    private Long userTenantAccessId;

    /** FK {@code account.id}. */
    private Integer accountId;

    private LocalDateTime createdAt;
}
