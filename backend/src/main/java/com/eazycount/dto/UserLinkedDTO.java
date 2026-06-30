package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLinkedDTO {
    private Integer id;
    private String name;
    private Integer tenantId;
    private Integer currencyId;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("linked_account_ids")
    private List<Integer> linkedAccountIds;

    @JsonProperty("unlinked_account_ids")
    private List<Integer> unlinkedAccountIds;

    @JsonProperty("linked_accounts")
    private List<UserLinkedDTO> linkedAccounts;

}
