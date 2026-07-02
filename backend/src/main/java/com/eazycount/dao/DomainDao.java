package com.eazycount.dao;

import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
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

    /*Create and Update Owner and Tenant*/
    void insertOwnerDetails(Owner owner);

    void insertTenantDetails(Tenant tenant);

    void updateOwnerDetails(Owner owner);

    void updateTenantDetails(Tenant tenant);

    /*List and Update Domain*/
    List<DomainFee> findAllDomainFee();

    void insertDomainFee(DomainFee domainFee);

    void updateDomainFee(DomainFee domainFee);
}
