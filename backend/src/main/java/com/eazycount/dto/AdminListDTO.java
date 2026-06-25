package com.eazycount.dto;

import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminListDTO {
    private Admin admin;
    private AdminTenantAccess adminTenantAccess;
}
