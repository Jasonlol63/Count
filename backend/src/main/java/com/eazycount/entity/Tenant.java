package com.eazycount.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code tenant} table (Scheme B: group and company in one ID space).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    private Integer id;

    @JsonProperty("tenant_type")
    private TenantType tenantType;

    /** Login / business code e.g. AP, 95, C168 */
    private String code;

    private String name;

    @JsonProperty("owner_id")
    private Integer ownerId;

    /** Subsidiary company → parent group tenant.id */
    @JsonProperty("parent_id")
    private Integer parentId;

    /** Payload only (e.g. frontend group_id); resolved to parentId on save. */
    @JsonProperty("parent_code")
    private String parentGroupCode;

    @JsonProperty("expiration_date")
    private LocalDate expirationDate;

    @JsonProperty("fee_share_allocate")
    private String feeShareAllocate;

    @JsonProperty("category_code")
    private List<String> categoryCode;

    private TenantStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum TenantType {
        GROUP("GROUP"),
        COMPANY("COMPANY");

        private final String value;

        TenantType(String value) {
            this.value = value;
        }

        public static TenantType fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (TenantType type : values()) {
                if (type.name().equalsIgnoreCase(normalized) || type.value.equalsIgnoreCase(normalized)) {
                    return type;
                }
            }

            throw new IllegalArgumentException("Unknown tenant type: " + value);
        }
    }

    @Getter
    public enum TenantStatus {
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private final String value;

        TenantStatus(String value) {
            this.value = value;
        }

        public static TenantStatus fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (TenantStatus status : values()) {
                if (status.name().equalsIgnoreCase(normalized) || status.value.equalsIgnoreCase(normalized)) {
                    return status;
                }
            }

            throw new IllegalArgumentException("Unknown tenant status: " + value);
        }
    }
}
