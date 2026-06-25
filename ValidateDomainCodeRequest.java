package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ValidateDomainCodeRequest {

    private String code;

    @JsonProperty("exclude_owner_id")
    private Integer excludeOwnerId;
}
