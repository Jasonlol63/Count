package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;
import java.util.Map;

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

    @JsonProperty("link_type")
    private String linkType;

    @JsonProperty("source_account_id")
    private Integer sourceAccountId;

    @JsonProperty("link_types_map")
    private Map<Integer, String> linkTypesMap;

    @JsonProperty("has_unidirectional")
    private Boolean hasUnidirectional;

}
