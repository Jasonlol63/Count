package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.DashboardDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardKpiRoleAmount;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.TenantOwnership;
import com.eazycount.entity.TenantOwnershipHistory;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String ROLE_PROFIT = "PROFIT";
    private static final String ROLE_EXPENSES = "EXPENSES";
    private static final int EARNINGS_SCALE = 8;

    @Autowired
    private DashboardDao dashboardDao;

    @Autowired
    private TenantDao tenantDao;

    @Override
    public DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo) {
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required");
        }
        if (dateFrom == null || dateTo == null) {
            throw new BusinessException("date_from and date_to are required");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BusinessException("date_from must not be after date_to");
        }

        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }

        DashboardKpiDTO dto = new DashboardKpiDTO();

        // Group-level rollup isn't implemented yet — a GROUP tenant gets all-null KPI
        // amounts (frontend renders "-"), same for Earnings.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            dto.setShowEarnings(false);
            return dto;
        }

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, BigDecimal> winLossByRole = toRoleMap(
                dashboardDao.aggregateWinLossByRole(tenantId, dateFrom, dateTo, roles));
        Map<String, BigDecimal> crDrByRole = toRoleMap(
                dashboardDao.aggregateCrDrByRole(tenantId, dateFrom, dateTo, roles));

        BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
        BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
        BigDecimal netProfit = profit.subtract(expenses);

        dto.setProfit(profit);
        dto.setExpenses(expenses);
        dto.setNetProfit(netProfit);

        applyEarnings(dto, tenantId, dateTo, netProfit);

        return dto;
    }

    /** Merges the Win/Loss and Cr/Dr buckets for one role; a role missing from either query counts as 0. */
    private static BigDecimal amountForRole(String role, Map<String, BigDecimal> winLossByRole,
                                             Map<String, BigDecimal> crDrByRole) {
        BigDecimal winLoss = winLossByRole.getOrDefault(role, BigDecimal.ZERO);
        BigDecimal crDr = crDrByRole.getOrDefault(role, BigDecimal.ZERO);
        return winLoss.add(crDr);
    }

    private static Map<String, BigDecimal> toRoleMap(List<DashboardKpiRoleAmount> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> row.getRole() == null ? "" : row.getRole().trim().toUpperCase(),
                DashboardKpiRoleAmount::getAmount,
                BigDecimal::add));
    }

    /**
     * Earnings only ever reflects the currently logged-in identity's own share — never
     * another account's. Only an Owner login, or an admin login with role=PARTNERSHIP,
     * can hold a company_ownership row (see TenantOwnership.getShareholderCandidates);
     * a "member" (ledger account) login never sees an Earnings card.
     */
    private void applyEarnings(DashboardKpiDTO dto, Integer tenantId, LocalDate dateTo, BigDecimal netProfit) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            dto.setShowEarnings(false);
            return;
        }

        String ownerType;
        Integer accountId = session.user_id;
        if ("owner".equalsIgnoreCase(session.user_type)) {
            ownerType = "owner";
        } else if ("user".equalsIgnoreCase(session.user_type) && "partnership".equalsIgnoreCase(session.role)) {
            ownerType = "user";
        } else {
            dto.setShowEarnings(false);
            return;
        }

        YearMonth ownershipMonth = YearMonth.from(dateTo);
        BigDecimal percentage;
        if (ownershipMonth.equals(YearMonth.now())) {
            TenantOwnership ownership = dashboardDao.findLiveOwnership(tenantId, accountId, ownerType);
            percentage = ownership != null ? ownership.getPercentage() : null;
        } else {
            TenantOwnershipHistory history = dashboardDao.findHistoricalOwnership(
                    tenantId, accountId, ownerType, ownershipMonth.atDay(1));
            percentage = history != null ? history.getPercentage() : null;
        }

        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            dto.setShowEarnings(false);
            return;
        }

        dto.setShowEarnings(true);
        dto.setEarningsPercentage(percentage);
        dto.setEarnings(netProfit
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
    }
}
