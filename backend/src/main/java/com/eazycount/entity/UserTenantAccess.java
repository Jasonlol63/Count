package com.eazycount.entity;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;


@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserTenantAccess {

    private Long id;

    private Integer accountId;

    private Integer tenantId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
