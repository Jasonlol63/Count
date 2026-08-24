package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/* Auto Renew delete/list request. Backs "AutoRenewController#delete" and "#list". */
@Getter
@Setter
public class AutoRenewRequestDTO {

    /* delete 专用 */
    @JsonProperty("request_id")
    private Integer requestId;

    /* list 专用过滤字段 */
    private String status;

    @JsonProperty("entity_type")
    private String entityType;

    @JsonProperty("date_from")
    private String dateFrom;

    @JsonProperty("date_to")
    private String dateTo;

    /* "pending_count" 时短路成侧栏徽章计数，不返回列表 */
    private String action;
}
