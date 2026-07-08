package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code user_tenant_process_access} (process whitelist under one user-tenant grant).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AdminTenantProcessAccess {

    private Long id;

    /** FK {@code user_tenant_access.id}. */
    private Long userTenantAccessId;

    /** FK {@code process.id}. */
    private Integer processId;

    private LocalDateTime createdAt;
}
