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

    /**
     * Domain Confirm "Charge on Save" trigger — not a column on {@code tenant}.
     * When true on an {@code update-setting} request, the saved Share % allocation
     * is charged against the Domain Fee for {@link #domainFeePeriod} and posted to
     * the C168 ledger as {@code transactions}. Never persisted; always false again
     * on the next read since it only exists for the duration of that one request.
     */
    private Boolean chargeDomainFeeOnConfirm;

    /** Renewal period code (e.g. "1year") used to look up the Domain Fee price when charging. Not persisted. */
    private String domainFeePeriod;

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
