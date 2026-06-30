package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

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
}
