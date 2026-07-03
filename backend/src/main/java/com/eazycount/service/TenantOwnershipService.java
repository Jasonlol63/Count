package com.eazycount.service;

import com.eazycount.dto.TenantOwnershipDTO;
import java.util.List;

public interface TenantOwnershipService {

    List<TenantOwnershipDTO> getOwnershipList(Integer tenantId, String groupCode, String month);

    List<TenantOwnershipDTO> getShareholderCandidates(String tenantIdStr);

    boolean isCurrentMonth(String month);
}
