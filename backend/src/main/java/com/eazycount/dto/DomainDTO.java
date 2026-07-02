package com.eazycount.dto;

import com.eazycount.entity.Tenant;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DomainDTO {
    private Integer id;

    @JsonProperty("tenant_id")
    private Integer tenantId;

    private String code;

    @JsonProperty("owner_code")
    private String ownerCode;

    private String name;

    private String email;

    private String password;

    @JsonProperty("secondary_password")
    private String secondaryPassword;

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("tenant_type")
    private Tenant.TenantType tenantType;

    private Integer parentId;

    private List<Tenant> groups;

    private List<Tenant> companies;
}
