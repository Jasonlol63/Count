package com.eazycount.service;

import com.eazycount.dto.CreateDomainRequest;
import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.DomainListItemDto;
import com.eazycount.dto.UpdateDomainRequest;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DomainService {

    List<DomainDTO> findAllDomainList();

    List<DomainListItemDto> listDomainsForApi();

    DomainListItemDto createDomain(CreateDomainRequest request);

    DomainListItemDto updateDomain(UpdateDomainRequest request);

    void validateDomainCode(String code, Integer excludeOwnerId);

    void addOwnerDetail(Owner owner);

    void updateOwnerDetail(Owner owner);

    void addTenantDetail(Tenant tenant);

    void updateTenantDetail(Tenant tenant);

    void deleteOwner(Integer ownerId);

    void deleteTenant(Integer tenantId);

    void deleteDomain(Integer domainId);

    DomainFee findFeeSetting();

    void updateFee(DomainFee domainFee);
}
