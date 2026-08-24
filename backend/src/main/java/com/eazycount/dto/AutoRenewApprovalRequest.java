package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AutoRenewApprovalRequest {

    @JsonProperty("request_id")
    private Integer requestId;

    /* approve 专用；reject 不传 */
    private String period;
}
