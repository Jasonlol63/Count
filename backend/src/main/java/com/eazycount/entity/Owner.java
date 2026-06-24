package com.eazycount.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code owner} table.
 */
@Getter
@Setter
@ToString(exclude = {"password", "secondaryPassword"})
@NoArgsConstructor
@AllArgsConstructor
public class Owner {

    private Integer id;

    private String ownerCode;

    private String name;

    private String email;

    private String password;

    private String secondaryPassword;

    private OwnerStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    @Getter
    public enum OwnerStatus {
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private final String value;

        OwnerStatus(String value) {
            this.value = value;
        }

        public static OwnerStatus fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (OwnerStatus status : values()) {
                if (status.name().equalsIgnoreCase(normalized) || status.value.equalsIgnoreCase(normalized)) {
                    return status;
                }
            }

            throw new IllegalArgumentException("Unknown status: " + value);
        }
    }
}
