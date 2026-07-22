package com.eazycount.service.impl;

import com.eazycount.dao.AutoRenewDao;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.DomainListFeePriceDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Owner;
import com.eazycount.entity.User;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AutoRenewService;
import com.eazycount.service.DomainFeeChargeService;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;

@Service
public class AutoRenewServiceImpl implements AutoRenewService {

    private static final Set<String> ALLOWED_PERIODS = Set.of(
            "7days", "1month", "3months", "6months", "1year");

    @Autowired
    private AutoRenewDao autoRenewDao;

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private DomainListFeePriceDao domainListFeePriceDao;

    @Autowired
    private DomainFeeChargeService domainFeeChargeService;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private UserDao userDao;

    private static final int WINDOW_DAYS = 30;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Map<String, Object> getAutoRenewCounts(String tenantType, int windowDays) {
        autoRenewDao.syncWindowRequests(windowDays);

        int pendingCnt = autoRenewDao.countRequestsByStatus("pending", tenantType, windowDays);
        int approvedCnt = autoRenewDao.countRequestsByStatus("approved", tenantType, windowDays);
        int rejectedCnt = autoRenewDao.countRequestsByStatus("rejected", tenantType, windowDays);
        int totalCnt = pendingCnt + approvedCnt + rejectedCnt;

        Map<String, Object> countsMap = new HashMap<>();
        countsMap.put("pending", pendingCnt);
        countsMap.put("approved", approvedCnt);
        countsMap.put("rejected", rejectedCnt);
        countsMap.put("total", totalCnt);

        int pendingCompany = autoRenewDao.countPendingByTenantType("COMPANY", windowDays);
        int pendingGroup = autoRenewDao.countPendingByTenantType("GROUP", windowDays);

        Map<String, Object> tabPendingCounts = new HashMap<>();
        tabPendingCounts.put("company", pendingCompany);
        tabPendingCounts.put("group", pendingGroup);

        Map<String, Object> stats = new HashMap<>();
        stats.put("counts", countsMap);
        stats.put("tab_pending_counts", tabPendingCounts);
        return stats;
    }

    @Override
    public Map<String, Object> getAutoRenewList(String status, String tenantType, String dateFromStr,
            String dateToStr) {

        LocalDate dateFrom = parseLocalDate(dateFromStr);
        LocalDate dateTo = parseLocalDate(dateToStr);
        String statusFilter = "all".equalsIgnoreCase(status) ? null : status;
        String tenantTypeFilter = tenantType != null ? tenantType.toUpperCase() : "COMPANY";

        autoRenewDao.syncWindowRequests(WINDOW_DAYS);

        List<AutoRenewDTO> rows = autoRenewDao.selectAutoRenewList(
                statusFilter,
                tenantTypeFilter,
                dateFrom,
                dateTo,
                WINDOW_DAYS);

        Tenant c168Tenant = tenantDao.findTenantByCode("C168");
        Integer c168TenantId = c168Tenant != null ? c168Tenant.getId() : null;
        List<UserListDTO> c168Users = new ArrayList<>();
        if (c168TenantId != null) {
            c168Users = userDao.findUserByTenantId(c168TenantId);
        }

        List<Map<String, Object>> accountsList = new ArrayList<>();
        for (UserListDTO u : c168Users) {
            if (u.getStatus() == User.AccountStatus.ACTIVE) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", u.getId());
                m.put("account_code", u.getAccountId());
                m.put("name", u.getName());
                accountsList.add(m);
            }
        }

        for (AutoRenewDTO row : rows) {
            Integer defaultToId = null;
            for (UserListDTO u : c168Users) {
                if (u.getStatus() == User.AccountStatus.ACTIVE && "C168".equalsIgnoreCase(u.getAccountId())) {
                    defaultToId = u.getId();
                    break;
                }
            }
            row.setDefaultToAccountId(defaultToId);
            if (row.getToAccountId() == null) {
                row.setToAccountId(defaultToId);
            }

            Integer defaultFromId = null;
            String compCode = row.getCompanyCode();
            if (compCode != null && !compCode.trim().isEmpty()) {
                String codeClean = compCode.trim().toUpperCase();
                for (UserListDTO u : c168Users) {
                    if (u.getStatus() == User.AccountStatus.ACTIVE && codeClean.equalsIgnoreCase(u.getAccountId())) {
                        defaultFromId = u.getId();
                        break;
                    }
                }
                if (defaultFromId == null && row.getOwnerId() != null) {
                    Owner owner = domainDao.findOwnerById(row.getOwnerId());
                    if (owner != null && owner.getOwnerCode() != null) {
                        String ownerCodeClean = owner.getOwnerCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                        String legacyCode = ownerCodeClean + "_" + codeClean;
                        for (UserListDTO u : c168Users) {
                            if (u.getStatus() == User.AccountStatus.ACTIVE
                                    && legacyCode.equalsIgnoreCase(u.getAccountId())) {
                                defaultFromId = u.getId();
                                break;
                            }
                        }
                    }
                }
            }
            row.setDefaultFromAccountId(defaultFromId);
            if (row.getFromAccountId() == null) {
                row.setFromAccountId(defaultFromId);
            }

            if (row.getFromAccountId() != null) {
                for (UserListDTO u : c168Users) {
                    if (u.getId().equals(row.getFromAccountId())) {
                        row.setFromAccountCode(u.getAccountId());
                        break;
                    }
                }
            }
            if (row.getToAccountId() != null) {
                for (UserListDTO u : c168Users) {
                    if (u.getId().equals(row.getToAccountId())) {
                        row.setToAccountCode(u.getAccountId());
                        break;
                    }
                }
            }

            boolean accountsResolved = row.getFromAccountId() != null && row.getToAccountId() != null
                    && !row.getFromAccountId().equals(row.getToAccountId());
            row.setCanApprove(
                    "pending".equalsIgnoreCase(row.getStatus()) && !row.getIsPaymentDeleted() && accountsResolved);
            row.setCanDelete("approved".equalsIgnoreCase(row.getStatus()) && row.getRequestId() != null
                    && row.getRequestId() > 0 && row.getTransactionId() != null && !row.getIsPaymentDeleted());
        }

        DomainFeeSettingsDTO feeSettings = domainService.findDomainFeeSettings();
        Map<String, Object> stats = this.getAutoRenewCounts(tenantTypeFilter, WINDOW_DAYS);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("rows", rows);
        responseData.put("accounts", accountsList);
        responseData.put("counts", stats.get("counts"));
        responseData.put("tab_pending_counts", stats.get("tab_pending_counts"));
        responseData.put("fee_settings", feeSettings);
        responseData.put("can_edit", true);

        return responseData;
    }

    private LocalDate parseLocalDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public void rejectRequest(Integer requestId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        if (requestId == null || requestId <= 0) {
            throw new BusinessException("Invalid request id");
        }

        AutoRenewDTO request = autoRenewDao.selectRequestById(requestId);
        if (request == null) {
            throw new BusinessException("Auto renew request not found");
        }

        if (!"pending".equalsIgnoreCase(request.getStatus())) {
            throw new BusinessException("Auto renew request is not pending");
        }

        String processedBy = session.login_id != null ? session.login_id : "system";
        autoRenewDao.rejectRequest(requestId, processedBy);
    }

    @Override
    @Transactional
    public Map<String, Object> approveRequest(Integer requestId, String periodRaw) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        if (requestId == null || requestId <= 0) {
            throw new BusinessException("Invalid request id");
        }

        String period = periodRaw != null ? periodRaw.trim() : "";
        if (!ALLOWED_PERIODS.contains(period)) {
            throw new BusinessException("Invalid renewal period");
        }

        AutoRenewDTO request = autoRenewDao.selectRequestById(requestId);
        if (request == null) {
            throw new BusinessException("Auto renew request not found");
        }
        if (!"pending".equalsIgnoreCase(request.getStatus())) {
            throw new BusinessException("Auto renew request is not pending");
        }

        Tenant tenant = domainDao.findTenantById(request.getTenantId());
        if (tenant == null || tenant.getId() == null) {
            throw new BusinessException("Tenant not found for auto renew request");
        }

        Tenant.TenantType tenantType = tenant.getTenantType() != null
                ? tenant.getTenantType()
                : Tenant.TenantType.COMPANY;

        BigDecimal price = domainListFeePriceDao.findPriceByTenantTypeAndPeriod(tenantType, period);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Domain fee price is not configured for period: " + period);
        }
        price = price.setScale(2, RoundingMode.HALF_UP);

        // Option A: extend from current tenant expiration (fallback today if unset)
        LocalDate baseExpiration = tenant.getExpirationDate() != null
                ? tenant.getExpirationDate()
                : LocalDate.now();
        LocalDate newExpiration = addPeriod(baseExpiration, period);

        // Same Domain Fee + Commission ledger postings as Domain Confirm Charge on Save
        int txnCount = domainFeeChargeService.chargeDomainFee(tenant, period);

        autoRenewDao.updateTenantExpiration(tenant.getId(), newExpiration);

        String processedBy = session.login_id != null ? session.login_id : "system";
        autoRenewDao.approveRequest(requestId, period, price, newExpiration, processedBy);

        Map<String, Object> data = new HashMap<>();
        data.put("request_id", requestId);
        data.put("tenant_id", tenant.getId());
        data.put("period", period);
        data.put("price", price);
        data.put("new_expiration_date", newExpiration.toString());
        data.put("transaction_count", txnCount);
        return data;
    }

    /** Align with frontend calculateExpirationDate period math. */
    static LocalDate addPeriod(LocalDate base, String period) {
        return switch (period) {
            case "7days" -> base.plusDays(7);
            case "1month" -> base.plusMonths(1);
            case "3months" -> base.plusMonths(3);
            case "6months" -> base.plusMonths(6);
            case "1year" -> base.plusYears(1);
            default -> throw new BusinessException("Invalid renewal period");
        };
    }
}
