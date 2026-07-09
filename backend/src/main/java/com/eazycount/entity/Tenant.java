package com.eazycount.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code tenant} table (group and company share one ID space).
 * <p>
 * Business modules are linked via {@code tenant_feature_module} → {@code feature_module}
 * ({@link #featureModules}), not stored as a JSON column on this row.
 * <p>
 * No Jackson field annotations — API uses Java camelCase property names.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    private Integer id;

    private TenantType tenantType;

    private String code;

    private String name;

    private Integer ownerId;

    private Integer parentId;

    private String parentGroupCode;

    private LocalDate expirationDate;

    private TenantStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum TenantType {
        GROUP,
        COMPANY
    }

    @Getter
    public enum TenantStatus {
        ACTIVE,
        INACTIVE
    }
}
