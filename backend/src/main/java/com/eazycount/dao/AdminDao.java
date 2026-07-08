package com.eazycount.dao;

import com.eazycount.dto.AdminDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminDao {

    /** Staff/admin rows linked to the given tenant via {@code user_tenant_access}. */
    List<AdminDTO> findAdminsByTenantId(@Param("tenantId") int tenantId);

    /** One admin row by tenant scope and login id. */
    AdminDTO findAdminByTenantIdAndLoginId(@Param("tenantId") int tenantId, @Param("loginId") String loginId);

    Admin findDuplicateLoginId(@Param("loginId") String loginId);

    Admin findDuplicateEmail(@Param("email") String email);

    void insertAdmin(Admin admin);

    void insertAdminTenantAccess(AdminTenantAccess access);

    void insertAdminTenantAccountAccessBatch(@Param("list") List<AdminTenantAccountAccess> list);

    void insertAdminTenantProcessAccessBatch(@Param("list") List<AdminTenantProcessAccess> list);
}
