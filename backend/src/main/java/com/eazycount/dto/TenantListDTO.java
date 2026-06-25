package com.eazycount.dto;

import com.eazycount.entity.Tenant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenantListDTO {
    private Integer id;
    private Tenant.TenantType tenantType;
    private String code;
    private String name;
    private Integer ownerId;
    private Integer parentId;
    private String parentGroupCode;
    private LocalDate expirationDate;
    private Tenant.TenantStatus status;
}
