package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to the {@code user} table (Admin tab login).
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

    private UserRole role;

    private String permissions;

    private UserStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime lastLogin;

    private String rememberToken;

    private LocalDateTime rememberTokenExpires;

    private Boolean readOnly;

    @Getter
    public enum UserRole {
        ADMIN("ADMIN"),
        MANAGER("MANAGER"),
        SUPERVISOR("SUPERVISOR"),
        ACCOUNTANT("ACCOUNTANT"),
        AUDIT("AUDIT"),
        CUSTOMER_SERVICE("CUSTOMER_SERVICE"),
        PARTNERSHIP("PARTNERSHIP");

        private final String value;

        UserRole(String value) {
            this.value = value;
        }

        public static UserRole fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (UserRole role : values()) {
                if (role.name().equalsIgnoreCase(normalized) || role.value.equalsIgnoreCase(normalized)) {
                    return role;
                }
            }

            if ("customer service".equalsIgnoreCase(normalized)) {
                return CUSTOMER_SERVICE;
            }

            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }

    @Getter
    public enum UserStatus {
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private final String value;

        UserStatus(String value) {
            this.value = value;
        }

        public static UserStatus fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (UserStatus status : values()) {
                if (status.name().equalsIgnoreCase(normalized) || status.value.equalsIgnoreCase(normalized)) {
                    return status;
                }
            }

            throw new IllegalArgumentException("Unknown status: " + value);
        }
    }

    public enum PermissionType{
        HOME("HOME"),
        DOMAIN("DOMAIN"),
        ANNOUNCEMENTS("ANNOUNCEMENTS"),
        AUTORENEW("AUTORENEW"),
        OWNERSHIP("OWNERSHIP"),
        ADMIN("ADMIN"),
        ACCOUNT("ACCOUNT"),
        PROCESS("PROCESS"),
        DATACAPTURE("DATACAPTURE"),
        TRANSACTION("TRANSACTION"),
        REPORT("REPORT"),
        MAINTENANCE("MAINTENANCE");

        private final String value;

        PermissionType(String value) {
            this.value = value;
        }

        public static PermissionType fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (PermissionType type : values()) {
                if (type.name().equalsIgnoreCase(normalized) || type.value.equalsIgnoreCase(normalized)) {
                    return type;
                }
            }

            throw new IllegalArgumentException("Unknown permission type: " + value);
        }
    }
}