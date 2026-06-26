package com.eazycount.dao;

import com.eazycount.dto.AdminListDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminDao {

    List<AdminListDTO> findAdminsByTenantId(int tenantId);

    AdminListDTO findAdminByUserIdAndTenantId(@Param("userId") int userId, @Param("tenantId") int tenantId);

    List<Integer> findTenantIdsByUserId(@Param("userId") int userId);

    void addAdmin(Admin admin);

    void insertAdminTenantAccess(AdminTenantAccess adminTenantAccess);

    void updateAdmin(Admin admin);

    void updateAdminTenantAccess(AdminTenantAccess adminTenantAccess);

    int countEmailExceptUser(@Param("email") String email, @Param("userId") int userId);

    void deleteAdminTenantAccessByUserIdAndTenantId(@Param("userId") int userId, @Param("tenantId") int tenantId);

    void updateStatusByAdminId(@Param("userId") int userId, @Param("status") Admin.UserStatus status);

    int deleteAdminByIdAndStatus(@Param("userId") int userId, @Param("status") Admin.UserStatus status);
}

