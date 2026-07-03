package com.eazycount.dto;

import com.eazycount.entity.Tenant.TenantType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AutoRenewDTO {
    // 自动续费申请记录的字段 (tenant_auto_renew_request)
    @JsonProperty("request_id")
    private Integer requestId;
    
    private String status;
    private String period;
    private BigDecimal price;
    
    @JsonProperty("new_expiration_date")
    private LocalDate newExpirationDate;
    
    @JsonProperty("processed_by")
    private String processedBy;
    
    @JsonProperty("processed_at")
    private LocalDateTime processedAt;
    
    // 关联租户的字段 (tenant)
    @JsonProperty("tenant_id")
    private Integer tenantId;
    
    @JsonProperty("tenant_type")
    private TenantType tenantType;   // "COMPANY" 或 "GROUP"
    
    @JsonProperty("company_code")
    private String companyCode;      
    
    @JsonProperty("expiration_date")
    private LocalDate expirationDate;
    
    @JsonProperty("days_until_expiration")
    private Integer daysUntilExpiration; 

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

    @JsonProperty("is_payment_deleted")
    private Boolean isPaymentDeleted = false;

    @JsonProperty("deleted_payment_id")
    private Integer deletedPaymentId;

    @JsonProperty("can_delete")
    private Boolean canDelete = false;

    @JsonProperty("can_approve")
    private Boolean canApprove = false;

    @JsonProperty("transaction_id")
    private Integer transactionId;

    // 动态属性计算用于渲染 UI 到期状态 Badge
    @JsonProperty("expiration_status")
    public String getExpirationStatus() {
        if (daysUntilExpiration == null) {
            return "normal";
        }
        if (daysUntilExpiration < 0) {
            return "expired";
        }
        if (daysUntilExpiration <= 7) {
            return "danger";
        }
        if (daysUntilExpiration <= 30) {
            return "warning";
        }
        return "normal";
    }

    // 动态属性映射用于处理人显示
    @JsonProperty("submitter")
    public String getSubmitter() {
        return processedBy;
    }

    @JsonProperty("submitter_at")
    public LocalDateTime getSubmitterAt() {
        return processedAt;
    }

    @JsonProperty("entity_type")
    public String getEntityType() {
        return tenantType != null ? tenantType.name().toLowerCase() : null;
    }
}
