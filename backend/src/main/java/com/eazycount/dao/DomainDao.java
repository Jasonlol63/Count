package com.eazycount.dao;

import com.eazycount.dto.DomainDTO;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DomainDao {

    List<DomainDTO> findAllDomainList();

    List<Tenant> findTenantsByOwnerId(Integer ownerId);

    int countTenantCodeConflict(@Param("code") String code, @Param("excludeOwnerId") Integer excludeOwnerId);

    int countOwnerCodeConflict(@Param("code") String code, @Param("excludeOwnerId") Integer excludeOwnerId);

    void addOwnerDetail(Owner owner);

    void updateOwnerDetail(Owner owner);

    void addTenantDetail(Tenant tenant);

    void updateTenantDetail(Tenant tenant);

    void deleteOwner(Integer ownerId);

    void deleteTenant(Integer tenantId);

    void deleteTenantFeatureModulesByTenantId(Integer tenantId);

    void deleteUserTenantAccessByTenantId(Integer tenantId);

    void deleteAccountTenantAccessByTenantId(Integer tenantId);

    void deleteTenantLinksByTenantId(Integer tenantId);

    void deletePasswordResetTacByTenantId(Integer tenantId);

    void deletePasswordResetTacOwnerByOwnerId(Integer ownerId);

    DomainFee findFeeSetting();

    void updateFee(DomainFee domainFee);
}
