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

    void insertOwnerDetails(Owner owner);

    void insertTenantDetails(Tenant tenant);

    DomainDTO createDomain(DomainDTO domainDTO);

    /*List and Update Domain Fee*/
    List<DomainFee> findAllDomainFee();

    DomainFee updateDomainFee(DomainFee domainFee);

}
