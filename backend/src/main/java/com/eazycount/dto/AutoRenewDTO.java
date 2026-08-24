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
    
    private Integer requestId;

    private String status;
    private String period;
    private BigDecimal price;

    private LocalDate newExpirationDate;

    private LocalDate expirationSnapshot;
    
    private String processedBy;
    private LocalDateTime processedAt;
    
    private Integer tenantId;
    private TenantType tenantType;

    private String entityType;
    private String companyCode;

    private LocalDate expirationDate;
    private Integer daysUntilExpiration;
    private String expirationStatus;

    private String ownerName;
    private Integer ownerId;

    private String groupId;

    private Integer fromAccountId;
    private Integer toAccountId;
    private Integer defaultFromAccountId;
    private Integer defaultToAccountId;
    private String fromAccountCode;
    private String toAccountCode;

    private Boolean canDelete = false;
    private Boolean canApprove = false;

    private Integer transactionCount;
    private Counts counts;
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
