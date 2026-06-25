package com.eazycount.dao;

import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PermissionDao {

    Tenant findTenantById(@Param("tenantId") int tenantId);
}
