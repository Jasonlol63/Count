package com.eazycount.service;

import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.*;

import java.util.List;

public interface DomainService {
    /*List Owner and Tenant Details*/
    List<OwnerTenantDTO> findAllTenantsByOwner(Integer ownerId);

    /* List, Insert, Delete Fee Share Allocation */
    List<TenantFeeShareAllocate> findFeeShareByTenantId(Integer tenantId);

    void deleteByTenantId(Integer tenantId);

    void batchInsert(List<TenantFeeShareAllocate> list);

    /* List, Insert, Delete Feature Modules */
    List<FeatureModule> findFeatureModulesByTenantId(Integer tenantId);

    void deleteFeatureModulesByTenantId(Integer tenantId);

    void batchInsertFeatureModules(List<TenantFeatureModule> list);

    /*Insert, Update and Delete Owner and Tenant Details*/
    void insertOwnerDetails(Owner owner);

    void insertTenantDetails(Tenant tenant);

    void updateOwnerDetails(Owner owner);

    void updateTenantDetails(Tenant tenant);

    void updateTenantDetailsSetting(Tenant tenant);

    void deleteOwnerDetails(Owner owner);

    void deleteTenantDetails(Tenant tenant);

    /*Main Domain CRUD*/
    DomainDTO createDomain(DomainDTO domainDTO);

    DomainDTO updateDomain(DomainDTO domainDTO);

    /* Domain list fee (domain_list_fee_price) */
    List<DomainListFeePrice> findAllDomainListFeePrices();

    List<RenewalPeriod> findAllRenewalPeriods();

    DomainFeeSettingsDTO findDomainFeeSettings();

    DomainFeeSettingsDTO updateDomainFeeSettings(DomainFeeSettingsDTO settings);

}
