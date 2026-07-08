package com.eazycount.service;

import com.eazycount.dto.AdminDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;

import java.util.List;

public interface AdminService {

    List<AdminDTO> findAdminsByTenantId(Integer tenantId);

    void insertAdmin(Admin admin);

    void insertAdminTenantAccess(AdminTenantAccess access);

    void insertAdminTenantAccountAccessBatch(List<AdminTenantAccountAccess> list);

    void insertAdminTenantProcessAccessBatch(List<AdminTenantProcessAccess> list);
}
