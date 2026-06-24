package com.eazycount.dto;

import com.eazycount.entity.Admin;
import com.eazycount.entity.Tenant;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminTenantDTO {
    private Admin admin;
    private Tenant tenant;
}
