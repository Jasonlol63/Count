package com.eazycount.service;

import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;

import java.util.List;

public interface DomainService {
    /*List Owner and Tenant Details*/
    List<OwnerTenantDTO> findAllTenantsByOwner(Integer ownerId);

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

    /*List and Update Domain Fee*/
    List<DomainFee> findAllDomainFee();

    DomainFee updateDomainFee(DomainFee domainFee);

}
