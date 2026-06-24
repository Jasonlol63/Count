package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Tenant row for domain list / read APIs (maps to {@code tenant} table). */
@Getter
@Setter
@NoArgsConstructor
public class TenantBriefDto {

    private String code;

    @JsonProperty("tenant_type")
    private String tenantType;

    @JsonProperty("expiration_date")
    private LocalDate expirationDate;

    /** Parent group business code; only set when {@code tenant_type} is COMPANY. */
    @JsonProperty("parent_code")
    private String parentCode;
}
