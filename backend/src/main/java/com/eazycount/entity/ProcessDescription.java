package com.eazycount.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDescription {
    private Integer id;
    private Integer tenantId;
    private String name;
    private LocalDateTime createdAt;
}
