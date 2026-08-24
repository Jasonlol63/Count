package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/* One C168 ACTIVE account option for the Auto Renew approve from/to account pickers. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoRenewAccountOptionDTO {

    private Integer id;

    @JsonProperty("account_code")
    private String accountCode;

    private String name;
}
