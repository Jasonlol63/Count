package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code user_role} — staff/admin role dictionary ({@code user.role_id}).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AdminRole {

    private Integer id;

    /** Machine code e.g. ADMIN, CUSTOMER_SERVICE */
    private String code;

    private String name;

    /** Lower value = higher privilege. */
    private Integer hierarchyLevel;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
