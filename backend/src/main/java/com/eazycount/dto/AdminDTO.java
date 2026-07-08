package com.eazycount.dto;

import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminDTO {

    private Admin admin;

    private AdminTenantAccess adminTenantAccess;

    private AdminTenantProcessAccess adminTenantProcessAccess;

    private AdminTenantAccountAccess adminTenantAccountAccess;
}
