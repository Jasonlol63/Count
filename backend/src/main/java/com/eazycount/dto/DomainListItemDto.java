package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class DomainListItemDto {

    private Integer id;

    @JsonProperty("owner_code")
    private String ownerCode;

    private String name;

    private String email;

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    private List<TenantBriefDto> groups = new ArrayList<>();

    private List<TenantBriefDto> companies = new ArrayList<>();
}
