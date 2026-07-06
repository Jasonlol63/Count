package com.eazycount.dao;

import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Owner;
import com.eazycount.entity.TenantOwnership;
import com.eazycount.entity.TenantOwnershipHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TenantOwnershipDao {
    // get ownership list and account option selection
    List<TenantOwnershipDTO> getActiveOwnershipList(@Param("tenantId") Integer tenantId);

    List<TenantOwnershipDTO> getHistoricalOwnershipList(@Param("tenantId") Integer tenantId, @Param("effectiveMonth") String effectiveMonth);

    List<TenantOwnershipDTO> getShareholderCandidates(@Param("tenantId") Integer tenantId);

    Tenant findTenantByCode(@Param("code") String code);

    Owner findOwnerById(@Param("id") Integer id);

    Owner findOwnerByCode(@Param("code") String code);

    Tenant findGroupByCode(@Param("code") String code);

    //link ownership
    void insertLink(TenantOwnership tenantOwnership);

    //save batch ownership at one time, delete it also
    void batchInsertLiveOwnership(@Param("list") List<TenantOwnership> list);

    void batchInsertHistoricalOwnership(@Param("list") List<TenantOwnershipHistory> list);

    void deleteHistoricalOwnership(@Param("tenantId") Integer tenantId, @Param("effectiveMonth") String effectiveMonth);

    void deleteLiveOwnership(@Param("tenantId") Integer tenantId);
}
