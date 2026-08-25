package com.eazycount.dto;

import com.eazycount.entity.Tenant.TenantType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoRenewDTO {

    @JsonProperty("request_id")
    private Integer requestId;

    private String status;
    private String period;
    private BigDecimal price;

    @JsonProperty("new_expiration_date")
    private LocalDate newExpirationDate;

    @JsonProperty("expiration_snapshot")
    private LocalDate expirationSnapshot;

    @JsonProperty("processed_by")
    private String processedBy;
    @JsonProperty("processed_at")
    private LocalDateTime processedAt;

    @JsonProperty("tenant_id")
    private Integer tenantId;
    @JsonProperty("tenant_type")
    private TenantType tenantType;

    @JsonProperty("entity_type")
    private String entityType;
    @JsonProperty("company_code")
    private String companyCode;

    @JsonProperty("expiration_date")
    private LocalDate expirationDate;
    @JsonProperty("days_until_expiration")
    private Integer daysUntilExpiration;
    @JsonProperty("expiration_status")
    private String expirationStatus;

    @JsonProperty("owner_name")
    private String ownerName;
    @JsonProperty("owner_id")
    private Integer ownerId;

    @JsonProperty("group_id")
    private String groupId;

    @JsonProperty("from_account_id")
    private Integer fromAccountId;
    @JsonProperty("to_account_id")
    private Integer toAccountId;
    @JsonProperty("default_from_account_id")
    private Integer defaultFromAccountId;
    @JsonProperty("default_to_account_id")
    private Integer defaultToAccountId;
    @JsonProperty("from_account_code")
    private String fromAccountCode;
    @JsonProperty("to_account_code")
    private String toAccountCode;

    @JsonProperty("can_delete")
    private Boolean canDelete = false;
    @JsonProperty("can_approve")
    private Boolean canApprove = false;

    @JsonProperty("transaction_count")
    private Integer transactionCount;
    private Counts counts;
    @JsonProperty("tab_pending_counts")
    private TabPendingCounts tabPendingCounts;

    // 动态属性映射用于处理人显示
    @JsonProperty("submitter")
    public String getSubmitter() {
        return processedBy;
    }

    @JsonProperty("submitter_at")
    public LocalDateTime getSubmitterAt() {
        return processedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {
        private Integer pending;
        private Integer approved;
        private Integer rejected;
        private Integer total;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TabPendingCounts {
        private Integer company;
        private Integer group;
    }
}
