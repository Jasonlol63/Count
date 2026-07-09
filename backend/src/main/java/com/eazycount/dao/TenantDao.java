package com.eazycount.dao;

import com.eazycount.dto.TenantDTO;
import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TenantDao {

  Tenant findTenantById(@Param("tenantId") int tenantId);

  Tenant findTenantByCode(@Param("code") String code);

  List<TenantDTO> findTenantFeaturesById(@Param("tenantId") int tenantId);

  List<TenantDTO> findTenantFeaturesByOwnerId(@Param("ownerId") int ownerId);

  List<TenantDTO> findTenantFeaturesByAdminId(@Param("adminId") int adminId);

  List<TenantDTO> findTenantFeaturesByMemberId(@Param("memberId") int memberId);

  /* Login lookup: tenants matching the entered group/company code (includes parent group match) */
  List<TenantDTO> findActiveTenantFeaturesByLoginCode(@Param("code") String code);

  List<FeatureModule> findActiveFeatureModulesByTenantId(@Param("tenantId") int tenantId);

  boolean hasActiveFeatureCode(@Param("tenantId") int tenantId, @Param("code") String code);
}
