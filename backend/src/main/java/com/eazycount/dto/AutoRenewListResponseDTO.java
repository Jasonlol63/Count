package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/* Auto Renew list page payload. Backs getAutoRenewList(). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoRenewListResponseDTO {

    private List<AutoRenewDTO> rows;

    private List<AutoRenewAccountOptionDTO> accounts;

    private AutoRenewDTO.Counts counts;

    @JsonProperty("tab_pending_counts")
    private AutoRenewDTO.TabPendingCounts tabPendingCounts;

    @JsonProperty("fee_settings")
    private DomainFeeSettingsDTO feeSettings;

    @JsonProperty("can_edit")
    private Boolean canEdit;
}
