package com.eazycount.dao;

import com.eazycount.entity.Permission;
import com.eazycount.entity.AdminRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PermissionDao {

    AdminRole findStaffRoleById(@Param("roleId") int roleId);

    AdminRole findStaffRoleByCode(@Param("code") String code);

    AdminRole findStaffRoleByUserId(@Param("userId") int userId);

    /** Default sidebar modules for a staff role ({@code user_role_permission}). */
    List<Permission> findActivePermissionsByRoleId(@Param("roleId") int roleId);

    /** Lookup by code — C168 runtime extras (DOMAIN, ANNOUNCEMENTS). */
    List<Permission> findActivePermissionsByCodes(@Param("codes") List<String> codes);
}
