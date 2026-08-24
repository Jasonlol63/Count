package com.eazycount.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoRenewTransactionDTO {

    private Integer id;

    @JsonProperty("request_id")
    private Integer requestId;

    @JsonProperty("transaction_id")
    private Integer transactionId;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
