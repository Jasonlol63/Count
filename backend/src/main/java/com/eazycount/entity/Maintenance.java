package com.eazycount.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Maintenance {
    private Integer id;
    private String prefix;
    private String content;
    private String companyCode;
    private Status status;
    private Integer createdBy;
    private User userType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @NoArgsConstructor
    public enum Status {
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private String value;

        Status(String value) {
            this.value = value;
        }

        public static Status fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (Status status : values()) {
                if (status.name().equalsIgnoreCase(normalized) || status.value.equalsIgnoreCase(normalized)) {
                    return status;
                }
            }

            throw new IllegalArgumentException("Unknown status: " + value);
        }
    }

    public enum User {
        USER("USER"),
        OWNER("OWNER");

        private final String value;

        User(String value) {
            this.value = value;
        }

        public static User fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (User user : values()) {
                if (user.name().equalsIgnoreCase(normalized) || user.value.equalsIgnoreCase(normalized)) {
                    return user;
                }
            }

            throw new IllegalArgumentException("Unknown userType: " + value);
        }
    }
}
