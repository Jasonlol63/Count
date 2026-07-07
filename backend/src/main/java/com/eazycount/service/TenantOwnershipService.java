package com.eazycount.service;

import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.entity.Tenant;
import java.util.List;
import java.util.Map;

public interface TenantOwnershipService {

    List<TenantOwnershipDTO> getOwnershipList(Integer tenantId, String month);

    List<TenantOwnershipDTO> getShareholderCandidates(Integer tenantId);

    boolean isCurrentMonth(String month);

    Map<String, Object> resolvePartner(Integer tenantId, String loginId, String forceType);

    Map<String, Object> linkPartner(Integer tenantId, String loginId, String forceType);

    Tenant findTenantByCode(String code);

    void saveLiveOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload);

    void saveHistoricalOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload, String effectiveMonth, Integer savedBy);

    void saveOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload, String month, List<String> retrofillMonths);

    void updateTenantParentId(Integer tenantId, String parentTenantCode);
}
