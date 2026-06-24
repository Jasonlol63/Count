package com.eazycount.dao;

import com.eazycount.dto.AdminTenantDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.UserDTO;
import com.eazycount.dto.UserTenantDTO;
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

    Admin findAdminByLoginId(@Param("loginId") String loginId);
    Owner findOwnerByOwnerCode(@Param("ownerCode") String ownerCode);
    User findMemberByAccountId(@Param("accountId") String accountId);

    List<Tenant> findActiveTenantsByLoginCode(@Param("tenantCode") String tenantCode);
}
