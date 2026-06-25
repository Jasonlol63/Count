package com.eazycount.dao;

import com.eazycount.dto.*;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuthDao {
    List<OwnerTenantDTO> findAccessibleTenantsByOwnerId(
            @Param("ownerId") Integer ownerId,
            @Param("tenantCode") String tenantCode
    );

    List<AdminTenantDTO> findAccessibleTenantsByAdminId(
            @Param("adminId") Integer adminId,
            @Param("tenantCode") String tenantCode
    );

    List<UserTenantDTO> findAccessibleTenantsByMemberId(
            @Param("userId") Integer userId,
            @Param("tenantCode") String tenantCode
    );

    List<TenantListDTO> findAllTenantsByOwnerId(@Param("ownerId") Integer ownerId);
    List<TenantListDTO> findAllTenantsByAdminId(@Param("adminId") Integer adminId);
    List<TenantListDTO> findAllTenantsByMemberId(@Param("userId") Integer userId);


    Admin findAdminByLoginId(@Param("loginId") String loginId);
    Owner findOwnerByOwnerCode(@Param("ownerCode") String ownerCode);
    User findMemberByAccountId(@Param("accountId") String accountId);

    List<Tenant> findActiveTenantsByLoginCode(@Param("tenantCode") String tenantCode);

    Admin findAdminSecondaryPasswordById(@Param("adminId") Integer adminId);

    Owner findOwnerSecondaryPasswordById(@Param("ownerId") Integer ownerId);
}
