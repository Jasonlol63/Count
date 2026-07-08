package com.eazycount.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to the {@code user} table — staff/admin identities (Admin tab login).
 * Role is stored as {@code role_id} → {@code user_role.id}; sidebar permissions come from
 * {@code user_role_permission}, not from this row.
 */
@Getter
@Setter
@ToString(exclude = {"password", "secondaryPassword", "rememberToken"})
@NoArgsConstructor
@AllArgsConstructor
public class Admin {

    private Integer id;

    private String loginId;

    private String name;

    private String password;

    private String secondaryPassword;

    private String email;

    /** FK {@code user_role.id} */
    private Integer roleId;

    /**
     * Populated when loaded with JOIN {@code user_role}; not a column on {@code user}.
     */
    private String roleCode;

    private UserStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime lastLogin;

    private String rememberToken;

    private LocalDateTime rememberTokenExpires;

    private Boolean readOnly;

    /** JSON field {@code role} for list/detail APIs (from joined {@code user_role.code}). */
    @JsonProperty("role")
    public String getRole() {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        return roleCode.trim();
    }

    @Getter
    public enum UserStatus {
        ACTIVE,
        INACTIVE;
    }
}
