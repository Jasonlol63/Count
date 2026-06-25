package com.eazycount.dao;

import com.eazycount.dto.AdminListDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AdminDao {

    List<AdminListDTO> findAdminsByTenantId(int tenantId);

    void addAdmin(Admin admin);

    void insertAdminTenantAccess(AdminTenantAccess adminTenantAccess);
}
