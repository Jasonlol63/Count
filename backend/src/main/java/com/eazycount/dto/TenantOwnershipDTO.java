package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenantOwnershipDTO {
    @JsonProperty("ownership_id")
    private Integer ownershipId;

    @JsonProperty("tenant_id")
    private Integer tenantId;

    private BigDecimal percentage;

    @JsonProperty("owner_type")
    private String ownerType;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("account_name")
    private String accountName;

    private String name;
    private String role;

    @JsonProperty("partner_group_id")
    private String partnerGroupId;

    @JsonProperty("partner_tenant_id")
    private Integer partnerTenantId;

    @JsonProperty("read_only")
    private Integer readOnly;

    @JsonProperty("is_external_partner")
    private Integer isExternalPartner;
}
