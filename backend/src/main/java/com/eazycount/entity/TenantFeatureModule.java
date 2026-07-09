package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenantFeatureModule {

    private Integer id;

    private Integer tenantId;

    private Integer moduleId;

    private LocalDateTime createdAt;
}
