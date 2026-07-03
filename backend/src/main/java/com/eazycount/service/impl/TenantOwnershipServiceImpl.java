package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TenantOwnershipDao;
import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TenantOwnershipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TenantOwnershipServiceImpl implements TenantOwnershipService {

    @Autowired
    private TenantOwnershipDao tenantOwnershipDao;

    @Override
    public List<TenantOwnershipDTO> getOwnershipList(Integer tenantId, String groupCode, String month) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        Integer resolvedTenantId = tenantId;
        if (resolvedTenantId == null) {
            if (groupCode == null || groupCode.isBlank()) {
                throw new BusinessException("tenant_id or group_code is required!");
            }
            Tenant groupTenant = tenantOwnershipDao.findTenantByCode(groupCode.trim());
            if (groupTenant == null) {
                throw new BusinessException("Group " + groupCode + " not found!");
            }
            resolvedTenantId = groupTenant.getId();
        }

        if (month == null || month.isBlank() || isCurrentMonth(month)) {
            // 查询实时表
            return tenantOwnershipDao.getActiveOwnershipList(resolvedTenantId);
        } else {
            String effectiveMonth = month.trim() + "-01";
            return tenantOwnershipDao.getHistoricalOwnershipList(resolvedTenantId, effectiveMonth);
        }
    }

    @Override
    public boolean isCurrentMonth(String month) {
        String currentMonthStr = YearMonth.now().toString();
        return currentMonthStr.equals(month.trim());
    }

    @Override
    public List<TenantOwnershipDTO> getShareholderCandidates(String tenantIdStr) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new BusinessException("tenant_id is required!");
        }

        Integer resolvedTenantId;
        try {
            resolvedTenantId = Integer.parseInt(tenantIdStr.trim());
        } catch (NumberFormatException e) {
            // Treat it as group code
            Tenant groupTenant = tenantOwnershipDao.findTenantByCode(tenantIdStr.trim());
            if (groupTenant == null) {
                throw new BusinessException("Group " + tenantIdStr + " not found!");
            }
            resolvedTenantId = groupTenant.getId();
        }

        List<TenantOwnershipDTO> candidates = tenantOwnershipDao.getShareholderCandidates(resolvedTenantId);

        List<TenantOwnershipDTO> existing = tenantOwnershipDao.getActiveOwnershipList(resolvedTenantId);
        Set<String> existingIds = existing.stream()
                .map(TenantOwnershipDTO::getAccountId)
                .collect(Collectors.toSet());

        return candidates.stream()
                .filter(c -> !existingIds.contains(c.getAccountId()))
                .collect(Collectors.toList());
    }
}
