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

    List<AdminDTO> findAdminsByTenantId(@Param("tenantId") int tenantId);

    AdminDTO findAdminByUserIdAndTenantId(@Param("userId") int userId, @Param("tenantId") int tenantId);

    AdminDTO findAdminByTenantIdAndLoginId(@Param("tenantId") int tenantId, @Param("loginId") String loginId);

    Admin findAdminById(@Param("id") int id);

    Admin findDuplicateLoginId(@Param("loginId") String loginId);

    Admin findDuplicateEmail(@Param("email") String email);

    Admin findDuplicateEmailExcludingId(@Param("email") String email, @Param("excludeId") int excludeId);

    AdminTenantAccess findTenantAccessByUserIdAndTenantId(@Param("userId") int userId, @Param("tenantId") int tenantId);

    List<Integer> findAdminTenantIdsByUserId(@Param("userId") int userId);

    List<AdminDTO.AccountPermissionItem> findAccountPermissionsByUserTenantAccessId(
            @Param("userTenantAccessId") long userTenantAccessId);

    List<AdminDTO.ProcessPermissionItem> findProcessPermissionsByUserTenantAccessId(
            @Param("userTenantAccessId") long userTenantAccessId);

    //insert admin use
    void insertAdmin(Admin admin);

    void insertAdminTenantAccountAccessBatch(@Param("list") List<AdminTenantAccountAccess> list);

    void insertAdminTenantProcessAccessBatch(@Param("list") List<AdminTenantProcessAccess> list);

    void insertAdminTenantAccess(AdminTenantAccess access);

    //update admin use
    void updateAdmin(Admin admin);

    void updateAdminTenantAccess(AdminTenantAccess access);

    //delete admin use
    void deleteTenantAccessByUserIdAndTenantId(@Param("userId") int userId, @Param("tenantId") int tenantId);

    void deleteAdminByIdAndStatus(@Param("id") int id, @Param("status") Admin.UserStatus status);

    void deleteTenantAccessByUserIdExceptTenants(@Param("userId") int userId, @Param("tenantIds") List<Integer> tenantIds);

    void deleteAccountAccessByUserTenantAccessId(@Param("userTenantAccessId") long userTenantAccessId);

    void deleteProcessAccessByUserTenantAccessId(@Param("userTenantAccessId") long userTenantAccessId);

    //update status of admin
    void updateStatusById(@Param("id") Integer id, @Param("status") Admin.UserStatus status);
}
