package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Currency {
    private Integer id;
    private String tenantId;
    private String code;
    private SourceType syncSource;
    private Status status;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;

    @Getter
    public enum SourceType{
        MANUAL("MANUAL"),
        SUBSIDIARY("SUBSIDIARY");

        private final String value;
        SourceType(String value){
            this.value = value;
        }

        public static SourceType fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (SourceType syncSource : values()) {
                if (syncSource.name().equalsIgnoreCase(normalized)
                        || syncSource.value.equalsIgnoreCase(normalized)) {
                    return syncSource;
                }
            }

            throw new IllegalArgumentException("Unknown syncSource: " + value);
        }

    }

    @Getter
    public enum Status{
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private final String value;

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

}
