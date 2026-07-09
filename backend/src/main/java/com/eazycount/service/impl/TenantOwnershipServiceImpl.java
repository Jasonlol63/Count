package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dao.TenantOwnershipDao;
import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.TenantOwnership;
import com.eazycount.entity.TenantOwnershipHistory;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TenantOwnershipService;
import com.eazycount.util.TenantDtoHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TenantOwnershipServiceImpl implements TenantOwnershipService {

    @Autowired
    private TenantOwnershipDao tenantOwnershipDao;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private TenantDao tenantDao;

    @Override
    public List<TenantOwnershipDTO> getOwnershipList(Integer tenantId, String month) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        if (month == null || month.isBlank() || isCurrentMonth(month)) {
            // 查询实时表
            return tenantOwnershipDao.getActiveOwnershipList(tenantId);
        } else {
            String effectiveMonth = month.trim() + "-01";
            return tenantOwnershipDao.getHistoricalOwnershipList(tenantId, effectiveMonth);
        }
    }

    @Override
    public boolean isCurrentMonth(String month) {
        String currentMonthStr = YearMonth.now().toString();
        return currentMonthStr.equals(month.trim());
    }

    @Override
    public List<TenantOwnershipDTO> getShareholderCandidates(Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        List<TenantOwnershipDTO> candidates = tenantOwnershipDao.getShareholderCandidates(tenantId);

        List<TenantOwnershipDTO> existing = tenantOwnershipDao.getActiveOwnershipList(tenantId);
        Set<String> existingIds = existing.stream()
                .map(TenantOwnershipDTO::getAccountId)
                .collect(Collectors.toSet());

        return candidates.stream()
                .filter(c -> !existingIds.contains(c.getAccountId()))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> resolvePartner(Integer tenantId, String loginId, String forceType) {
        Map<String, Object> result = new HashMap<>();
        if (loginId == null || loginId.isBlank()) {
            result.put("status", "error");
            result.put("message", "Login ID or Group ID is required");
            return result;
        }
        String searchCode = loginId.trim();
        String type = forceType != null ? forceType.trim() : "";

        Owner partnerByLogin = null;
        if (type.isEmpty() || "login".equalsIgnoreCase(type)) {
            partnerByLogin = domainDao.findOwnerByCode(searchCode);
        }

        // 查询作为 Partner 的 Tenant (原本的 Group)
        Tenant partnerTenant = null;
        if (type.isEmpty() || "tenant".equalsIgnoreCase(type) || "group".equalsIgnoreCase(type)) {
            List<Tenant> activeTenants = TenantDtoHelper.distinctTenants(
                    tenantDao.findActiveTenantFeaturesByLoginCode(searchCode));
            if (activeTenants != null) {
                partnerTenant = activeTenants.stream()
                        .filter(t -> t.getTenantType() == Tenant.TenantType.GROUP && "ACTIVE".equalsIgnoreCase(t.getStatus().name()))
                        .findFirst()
                        .orElse(null);
            }
        }

        // 校验冲突情况
        if (partnerByLogin != null && partnerTenant != null
                && !partnerByLogin.getId().equals(partnerTenant.getOwnerId())) {
            result.put("status", "conflict");
            result.put("message", "Multiple matches found.");

            result.put("conflictData", Map.of(
                    "login_partner", partnerByLogin.getName() + " (" + partnerByLogin.getOwnerCode() + ")",
                    "tenant_partner", partnerTenant.getName() + " (Tenant: " + partnerTenant.getCode() + ")"
            ));
            return result;
        }

        // 解析最终实体
        if (partnerTenant != null) {
            Owner tenantOwner = domainDao.findOwnerById(partnerTenant.getOwnerId());
            if (tenantOwner == null) {
                result.put("status", "error");
                result.put("message", "Owner of Tenant '" + partnerTenant.getCode() + "' not found or inactive");
                return result;
            }
            result.put("status", "success");
            result.put("owner", tenantOwner);
            result.put("partnerTenant", partnerTenant);
        } else if (partnerByLogin != null) {
            result.put("status", "success");
            result.put("owner", partnerByLogin);
        } else {
            result.put("status", "error");
            result.put("message", "Owner account or Tenant ID not found or inactive");
        }
        return result;
    }

    @Override
    public Map<String, Object> linkPartner(Integer tenantId, String loginId, String forceType) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        if (!"owner".equalsIgnoreCase(sessionUser.role)) {
            throw new BusinessException("Read-only: only owner can modify ownership");
        }

        Tenant targetTenant = domainDao.findTenantById(tenantId);
        if (targetTenant == null) {
            throw new BusinessException("Tenant not found");
        }

        Map<String, Object> resolved = this.resolvePartner(tenantId, loginId, forceType);

        // 如果是冲突状态，直接将冲突信息返回给控制器，由控制器发回给前端
        if ("conflict".equals(resolved.get("status"))) {
            Map<String, Object> conflictResponse = new HashMap<>();
            conflictResponse.put("status", "conflict");
            conflictResponse.put("message", resolved.get("message"));
            conflictResponse.put("data", resolved.get("conflictData"));
            return conflictResponse;
        }

        if ("error".equals(resolved.get("status"))) {
            throw new BusinessException(resolved.get("message").toString());
        }

        Owner targetPartner = (Owner) resolved.get("owner");
        Tenant matchedTenant = (Tenant) resolved.get("partnerTenant");

        // 3. 安全约束校验
        Integer partnerOwnerId = targetPartner.getId();
        Integer nativeOwnerId = targetTenant.getOwnerId();
        boolean isSameOwnerTenantLink = (matchedTenant != null && partnerOwnerId.equals(nativeOwnerId));
        
        if (!isSameOwnerTenantLink && partnerOwnerId.equals(nativeOwnerId)) {
            throw new BusinessException("This account is already the main owner of this tenant");
        }
        if (isSameOwnerTenantLink && targetTenant.getId().equals(matchedTenant.getId())) {
            throw new BusinessException("Cannot link a tenant to itself");
        }

        // 内存过滤查重
        List<TenantOwnershipDTO> existingList = tenantOwnershipDao.getActiveOwnershipList(tenantId);
        boolean isDuplicate;
        if (isSameOwnerTenantLink) {
            String tenantAccountIdStr = "G_" + matchedTenant.getId();
            isDuplicate = existingList.stream()
                    .anyMatch(e -> "group".equalsIgnoreCase(e.getOwnerType()) && tenantAccountIdStr.equalsIgnoreCase(e.getAccountId()));
        } else {
            String ownerAccountIdStr = "O_" + partnerOwnerId;
            isDuplicate = existingList.stream()
                    .anyMatch(e -> "owner".equalsIgnoreCase(e.getOwnerType()) && ownerAccountIdStr.equalsIgnoreCase(e.getAccountId()));
        }
        if (isDuplicate) {
            throw new BusinessException(isSameOwnerTenantLink
                    ? "Partner Tenant is already linked to this tenant"
                    : "Partner is already linked to this tenant");
        }

        // 执行保存 (Insert)
        TenantOwnership newLink = new TenantOwnership();
        newLink.setTenantId(tenantId);
        newLink.setPercentage(BigDecimal.ZERO);
        newLink.setReadOnly(1);
        newLink.setSortOrder(0);
        if (isSameOwnerTenantLink) {
            newLink.setOwnerType("group");
            newLink.setAccountId(0);
            newLink.setPartnerTenantId(matchedTenant.getId());
        } else {
            newLink.setOwnerType("owner");
            newLink.setAccountId(partnerOwnerId);
            newLink.setPartnerTenantId(matchedTenant != null ? matchedTenant.getId() : null);
        }
        tenantOwnershipDao.insertLink(newLink);

        // 6. 返回成功信息
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", isSameOwnerTenantLink
                ? "Tenant '" + matchedTenant.getCode() + "' linked successfully"
                : "Partner '" + targetPartner.getName() + "' linked successfully");
        return response;
    }

    @Override
    public Tenant findTenantByCode(String code) {
        if (code == null) {
            return null;
        }
        return tenantOwnershipDao.findTenantByCode(code.trim());
    }

    @Override
    public void saveLiveOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload) {
        Map<String, Integer> existingPartnerTenantMap = new HashMap<>();
        List<TenantOwnershipDTO> existing = tenantOwnershipDao.getActiveOwnershipList(tenantId);
        for (TenantOwnershipDTO ext : existing) {
            if ("owner".equalsIgnoreCase(ext.getOwnerType()) && ext.getPartnerTenantId() != null) {
                existingPartnerTenantMap.put(ext.getAccountId(), ext.getPartnerTenantId());
            }
        }

        tenantOwnershipDao.deleteLiveOwnership(tenantId);

        List<TenantOwnership> liveList = new ArrayList<>();
        for (int i = 0; i < ownersPayload.size(); i++) {
            Map<String, Object> owner = ownersPayload.get(i);
            TenantOwnership row = new TenantOwnership();
            row.setTenantId(tenantId);
            row.setPercentage(new BigDecimal(owner.get("percentage").toString()));
            row.setReadOnly(owner.get("read_only") != null ? Integer.parseInt(owner.get("read_only").toString()) : 1);
            row.setSortOrder(owner.get("sort_order") != null ? Integer.parseInt(owner.get("sort_order").toString()) : i);

            String rawId = owner.get("account_id").toString();
            String ownerType = "owner";
            Integer realId = 0;
            Integer partnerTenantId = null;

            if (rawId.startsWith("G_")) {
                ownerType = "group";
                String grpCode = rawId.substring(2);
                try {
                    partnerTenantId = Integer.valueOf(grpCode);
                } catch (NumberFormatException e) {
                    Tenant grpTenant = tenantOwnershipDao.findTenantByCode(grpCode);
                    if (grpTenant != null) {
                        partnerTenantId = grpTenant.getId();
                    } else {
                        throw new BusinessException("Group '" + grpCode + "' not found");
                    }
                }
            } else if (rawId.startsWith("O_")) {
                ownerType = "owner";
                realId = Integer.valueOf(rawId.substring(2));
                partnerTenantId = existingPartnerTenantMap.get(rawId); // 还原备份的租户关联
            } else if (rawId.startsWith("U_")) {
                ownerType = "user";
                realId = Integer.valueOf(rawId.substring(2));
            }
            row.setOwnerType(ownerType);
            row.setAccountId(realId);
            row.setPartnerTenantId(partnerTenantId);
            liveList.add(row);
        }
        if (!liveList.isEmpty()) {
            tenantOwnershipDao.batchInsertLiveOwnership(liveList);
        }
    }

    @Override
    public void saveHistoricalOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload, String effectiveMonth, Integer savedBy) {

        tenantOwnershipDao.deleteHistoricalOwnership(tenantId, effectiveMonth);

        List<TenantOwnershipHistory> historyList = new ArrayList<>();
        for (Map<String, Object> row : ownersPayload) {
            TenantOwnershipHistory history = new TenantOwnershipHistory();
            history.setTenantId(tenantId);
            history.setEffectiveMonth(Date.valueOf(effectiveMonth).toLocalDate());
            history.setPercentage(new BigDecimal(row.get("percentage").toString()));
            history.setReadOnly(row.get("read_only") != null ? Integer.parseInt(row.get("read_only").toString()) : 1);
            history.setSavedBy(savedBy);
            String rawId = row.get("account_id").toString();
            String ownerType = "owner";
            Integer realId = 0;
            Integer partnerTenantId = null;
            if (rawId.startsWith("G_")) {
                ownerType = "group";
                String grpCode = rawId.substring(2);
                try {
                    partnerTenantId = Integer.valueOf(grpCode);
                } catch (NumberFormatException e) {
                    Tenant grpTenant = tenantOwnershipDao.findTenantByCode(grpCode);
                    if (grpTenant != null) {
                        partnerTenantId = grpTenant.getId();
                    } else {
                        throw new BusinessException("Group '" + grpCode + "' not found");
                    }
                }
            } else if (rawId.startsWith("O_")) {
                ownerType = "owner";
                realId = Integer.valueOf(rawId.substring(2));
            } else if (rawId.startsWith("U_")) {
                ownerType = "user";
                realId = Integer.valueOf(rawId.substring(2));
            }
            history.setOwnerType(ownerType);
            history.setAccountId(realId);
            history.setPartnerTenantId(partnerTenantId);
            historyList.add(history);
        }
        if (!historyList.isEmpty()) {
            tenantOwnershipDao.batchInsertHistoricalOwnership(historyList);
        }
    }

    @Override
    @Transactional
    public void saveOwnership(Integer tenantId, List<Map<String, Object>> ownersPayload, String month, List<String> retrofillMonths) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if(sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        if (!"owner".equalsIgnoreCase(sessionUser.role)) {
            throw new BusinessException("Read-only: only owner can modify ownership");
        }

        BigDecimal totalPct = BigDecimal.ZERO;
        for (Map<String, Object> row : ownersPayload) {
            BigDecimal pct = new BigDecimal(row.get("percentage").toString());
            boolean isExternal = row.get("is_external_partner") != null &&
                    ("1".equals(row.get("is_external_partner").toString()) || Boolean.parseBoolean(row.get("is_external_partner").toString()));
            if (isExternal) {
                if (pct.compareTo(BigDecimal.ZERO) != 0) {
                    throw new BusinessException("External partner rows must stay at 0%");
                }
                continue;
            }
            if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException("Percentage must be between 0 and 100");
            }
            totalPct = totalPct.add(pct);
        }
        if (totalPct.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("Total allocation exceeds 100%");
        }
        // 判定保存目标（历史月 vs 实时+当前月）
        boolean saveHistoryOnly = (month != null && !month.isBlank() && !isCurrentMonth(month));
        if (saveHistoryOnly) {
            // 分流处理：仅写入特定历史月份
            String effectiveMonth = month.trim() + "-01";
            this.saveHistoricalOwnership(tenantId, ownersPayload, effectiveMonth, sessionUser.user_id);
        } else { // 分流处理：同时写入实时表和当月快照表
            // 保存到实时表
            this.saveLiveOwnership(tenantId, ownersPayload);
            // 存入当前月份历史快照
            String currentEffectiveMonth = YearMonth.now().toString() + "-01";
            this.saveHistoricalOwnership(tenantId, ownersPayload, currentEffectiveMonth, sessionUser.user_id);
            // 处理历史月份的追溯写入
            if (retrofillMonths != null && !retrofillMonths.isEmpty()) {
                for (String retroMonth : retrofillMonths) {
                    String retroEffectiveMonth = retroMonth.trim() + "-01";
                    this.saveHistoricalOwnership(tenantId, ownersPayload, retroEffectiveMonth, sessionUser.user_id);
                }
            }
        }
    }

    @Override
    @Transactional
    public void updateTenantParentId(Integer tenantId, String parentTenantCode) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (!"owner".equalsIgnoreCase(sessionUser.role)) {
            throw new BusinessException("Read-only: only owner can modify ownership");
        }

        Tenant company =domainDao.findTenantById(tenantId);
        if (company == null) {
            throw new BusinessException("Tenant not found");
        }

        Tenant owner = domainDao.findOwnerTenantByIdAndOwnerId(tenantId, company.getOwnerId());
        if (owner == null) {
            throw new BusinessException("Tenant or Owner not found");
        }

        Integer parentId = null;
        String code = parentTenantCode != null ? parentTenantCode.trim() : "";
        if (!code.isBlank()) {
            Tenant group = tenantOwnershipDao.findTenantByCode(code);
            if (group == null) {
                throw new BusinessException("Parent Tenant not found");
            }
            parentId = group.getId();
        }

        try {
            tenantOwnershipDao.updateTenantParentId(tenantId, parentId, sessionUser.user_id);
        } catch (Exception e) {
            throw new BusinessException("Error updating tenant parent id");
        }
    }
}
