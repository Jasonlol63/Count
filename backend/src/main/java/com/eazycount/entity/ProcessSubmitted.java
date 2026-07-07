package com.eazycount.entity;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
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
