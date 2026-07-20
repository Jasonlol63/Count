package com.eazycount.dao;

import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DomainDao {
    /*List and Find Tenant in Domain*/
    List<OwnerTenantDTO> findAllTenantsByOwner(@Param("ownerId") Integer ownerId);

    Owner findOwnerById(@Param("id") Integer ownerId);

    Owner findOwnerByCode(@Param("ownerCode") String ownerCode);

    Tenant findTenantById(@Param("id") Integer id);

    Tenant findOwnerTenantByIdAndOwnerId(@Param("id") Integer id, @Param("ownerId") Integer ownerId);

    Tenant findTenantByCodeAndOwnerId(@Param("code") String code, @Param("ownerId") Integer ownerId);

    /*Create, Update and Delete Owner and Tenant*/
    void insertOwnerDetails(Owner owner);

    void insertTenantDetails(Tenant tenant);

    void updateOwnerDetails(Owner owner);

    void updateTenantDetails(Tenant tenant);

    void deleteOwnerDetails(Owner owner);

    void deleteTenantDetails(Tenant tenant);

    /* List, Insert, Delete Feature Modules */
    List<FeatureModule> findFeatureModulesByTenantId(@Param("tenantId") Integer tenantId);

    List<FeatureModule> findAllActiveFeatureModules();

    void deleteFeatureModulesByTenantId(@Param("tenantId") Integer tenantId);

    void batchInsertFeatureModules(@Param("list") List<TenantFeatureModule> list);

}
