package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountingDueInboxRequest {

    private Integer tenantId;

    private LocalDate asOf;

    private Boolean restoreSkipped;
}
