package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code submitted_processes} — one capture submission per tenant/process/date.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSubmitted {

    private Integer id;

    private Integer tenantId;

    private Integer processId;

    private Integer userId;

    private LocalDate captureDate;

    private LocalDateTime createdAt;
}
