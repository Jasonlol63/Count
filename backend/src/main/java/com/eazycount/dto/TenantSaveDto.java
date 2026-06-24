package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Tenant payload for domain save APIs (maps to {@code tenant} table). */
@Getter
@Setter
@NoArgsConstructor
public class TenantSaveDto {

    @JsonProperty("tenant_type")
    private String tenantType;

    private String code;

    @JsonProperty("expiration_date")
    private LocalDate expirationDate;

    /** Parent group business code; only for COMPANY rows. */
    @JsonProperty("parent_code")
    private String parentCode;
}
