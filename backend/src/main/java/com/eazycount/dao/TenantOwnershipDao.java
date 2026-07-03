package com.eazycount.dao;

import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TenantOwnershipDao {
    List<TenantOwnershipDTO> getActiveOwnershipList(@Param("tenantId") Integer tenantId);
    List<TenantOwnershipDTO> getHistoricalOwnershipList(@Param("tenantId") Integer tenantId, @Param("effectiveMonth") String effectiveMonth);
    List<TenantOwnershipDTO> getShareholderCandidates(@Param("tenantId") Integer tenantId);
    Tenant findTenantByCode(@Param("code") String code);
}
