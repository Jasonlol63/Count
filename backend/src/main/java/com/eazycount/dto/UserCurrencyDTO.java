package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCurrencyDTO {

    private Integer id;
    private String code;

    @JsonProperty("is_linked")
    private boolean isLinked;

    @JsonProperty("sync_source")
    private String syncSource;

    private boolean deletable;
}
