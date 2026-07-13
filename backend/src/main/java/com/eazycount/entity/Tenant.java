package com.eazycount.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code tenant} table (group and company share one ID space).
 * <p>
 * Business modules are linked via {@code tenant_feature_module} → {@code feature_module}
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

    /** Loaded from {@code tenant_fee_share_allocation}; not a column on {@code tenant}. */
    private List<TenantFeeShareAllocate> feeShareAllocations;

    /** Loaded from {@code tenant_feature_module}; not a column on {@code tenant}. */
    private List<FeatureModule> featureModules;

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
