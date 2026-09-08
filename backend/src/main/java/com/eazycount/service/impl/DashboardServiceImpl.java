package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.DashboardDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
    public DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required");
        }
        if (dateFrom == null || dateTo == null) {
            throw new BusinessException("date_from and date_to are required");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BusinessException("date_from must not be after date_to");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException("currency is required");
        }
        String currency = currencyCode.trim();

        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }

        DashboardKpiDTO dto = new DashboardKpiDTO();

        // GROUP tenants aren't supported yet — all-null KPI, no Earnings.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            dto.setShowEarnings(false);
            return dto;
        }

        ProfitExpenses current = computeProfitExpenses(tenantId, dateFrom, dateTo, currency);
        dto.setProfit(current.profit);
        dto.setExpenses(current.expenses);
        dto.setNetProfit(current.netProfit);
        applyEarnings(dto, tenantId, dateTo, current.netProfit);

        LocalDate[] previousRange = resolvePreviousRange(dateFrom, dateTo);
        LocalDate previousDateFrom = previousRange[0];
        LocalDate previousDateTo = previousRange[1];
        dto.setPreviousDateFrom(previousDateFrom);
        dto.setPreviousDateTo(previousDateTo);

        ProfitExpenses previous = computeProfitExpenses(tenantId, previousDateFrom, previousDateTo, currency);
        dto.setPreviousProfit(previous.profit);
        dto.setPreviousExpenses(previous.expenses);
        dto.setPreviousNetProfit(previous.netProfit);
        if (dto.isShowEarnings()) {
            dto.setPreviousEarnings(resolveEarningsAmount(tenantId, previousDateTo, previous.netProfit));
        }

        return dto;
    }

    @Override
    public List<DashboardTrendPointDTO> getTrend(Integer tenantId, LocalDate dateFrom, LocalDate dateTo,
                                                  String currencyCode) {
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required");
        }
        if (dateFrom == null || dateTo == null) {
            throw new BusinessException("date_from and date_to are required");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BusinessException("date_from must not be after date_to");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException("currency is required");
        }
        String currency = currencyCode.trim();

        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }

        // GROUP tenants aren't supported yet — empty series, frontend falls back to zero-skeleton rows.
        if (tenant.getTenantType() != Tenant.TenantType.COMPANY) {
            return List.of();
        }

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(tenantId, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(tenantId, dateFrom, dateTo, roles, currency));

        List<DashboardTrendPointDTO> points = new ArrayList<>();
        for (LocalDate date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            Map<String, BigDecimal> winLossByRole = winLossByDateRole.getOrDefault(date, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByDateRole.getOrDefault(date, Map.of());

            BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal netProfit = profit.add(expenses);

            points.add(new DashboardTrendPointDTO(date, profit, expenses, netProfit));
        }
        return points;
    }

    private static Map<LocalDate, Map<String, BigDecimal>> toDateRoleMap(List<DashboardTrendPointDTO.RoleAmount> rows) {
        Map<LocalDate, Map<String, BigDecimal>> byDate = new HashMap<>();
        for (DashboardTrendPointDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byDate.computeIfAbsent(row.getDate(), d -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byDate;
    }

    /*
     * Previous period: N whole calendar months -> previous N whole months (N=12 = last year);
     * anything else -> same day-count window immediately before.
     */
    private static LocalDate[] resolvePreviousRange(LocalDate dateFrom, LocalDate dateTo) {
        boolean isWholeCalendarMonths = dateFrom.getDayOfMonth() == 1
                && dateTo.equals(dateTo.withDayOfMonth(dateTo.lengthOfMonth()));
        if (isWholeCalendarMonths) {
            long monthSpan = ChronoUnit.MONTHS.between(
                    YearMonth.from(dateFrom).atDay(1), YearMonth.from(dateTo).atDay(1)) + 1;
            LocalDate previousDateTo = dateFrom.minusDays(1);
            LocalDate previousDateFrom = YearMonth.from(previousDateTo).minusMonths(monthSpan - 1).atDay(1);
            return new LocalDate[]{previousDateFrom, previousDateTo};
        }

        long lengthInDays = ChronoUnit.DAYS.between(dateFrom, dateTo) + 1;
        LocalDate previousDateTo = dateFrom.minusDays(1);
        LocalDate previousDateFrom = previousDateTo.minusDays(lengthInDays - 1);
        return new LocalDate[]{previousDateFrom, previousDateTo};
    }

    private ProfitExpenses computeProfitExpenses(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currency) {
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, BigDecimal> winLossByRole = toRoleMap(
                dashboardDao.aggregateWinLossByRole(tenantId, dateFrom, dateTo, roles, currency));
        Map<String, BigDecimal> crDrByRole = toRoleMap(
                dashboardDao.aggregateCrDrByRole(tenantId, dateFrom, dateTo, roles, currency));

        BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
        BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
        // expenses is already signed negative, so add (not subtract) gives profit - |expenses|.
        BigDecimal netProfit = profit.add(expenses);
        return new ProfitExpenses(profit, expenses, netProfit);
    }

    /* Win/Loss + Cr/Dr for one role; missing from either query counts as 0. */
    private static BigDecimal amountForRole(String role, Map<String, BigDecimal> winLossByRole,
                                             Map<String, BigDecimal> crDrByRole) {
        BigDecimal winLoss = winLossByRole.getOrDefault(role, BigDecimal.ZERO);
        BigDecimal crDr = crDrByRole.getOrDefault(role, BigDecimal.ZERO);
        return winLoss.add(crDr);
    }

    private static Map<String, BigDecimal> toRoleMap(List<DashboardKpiDTO.RoleAmount> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> row.getRole() == null ? "" : row.getRole().trim().toUpperCase(),
                DashboardKpiDTO.RoleAmount::getAmount,
                BigDecimal::add));
    }

    /* Earnings reflects only the current login's own share (owner, or PARTNERSHIP admin). */
    private void applyEarnings(DashboardKpiDTO dto, Integer tenantId, LocalDate dateTo, BigDecimal netProfit) {
        String ownerType = resolveOwnerType();
        if (ownerType == null) {
            dto.setShowEarnings(false);
            return;
        }

        BigDecimal percentage = findOwnershipPercentage(tenantId, dateTo, ownerType);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            dto.setShowEarnings(false);
            return;
        }

        dto.setShowEarnings(true);
        dto.setEarningsPercentage(percentage);
        dto.setEarnings(earningsFrom(netProfit, percentage));
    }

    /* Same as applyEarnings, for the previous period's netProfit. */
    private BigDecimal resolveEarningsAmount(Integer tenantId, LocalDate dateTo, BigDecimal netProfit) {
        String ownerType = resolveOwnerType();
        if (ownerType == null) {
            return null;
        }
        BigDecimal percentage = findOwnershipPercentage(tenantId, dateTo, ownerType);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return earningsFrom(netProfit, percentage);
    }

    /* "owner" / "user" (PARTNERSHIP admin) / null (member logins can't own shares). */
    private static String resolveOwnerType() {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            return null;
        }
        if ("owner".equalsIgnoreCase(session.user_type)) {
            return "owner";
        }
        if ("user".equalsIgnoreCase(session.user_type) && "partnership".equalsIgnoreCase(session.role)) {
            return "user";
        }
        return null;
    }

    /* Live table for the current month, monthly snapshot history table otherwise. */
    private BigDecimal findOwnershipPercentage(Integer tenantId, LocalDate dateTo, String ownerType) {
        Integer accountId = SecurityUtils.currentUser().user_id;
        YearMonth ownershipMonth = YearMonth.from(dateTo);
        if (ownershipMonth.equals(YearMonth.now())) {
            TenantOwnership ownership = dashboardDao.findLiveOwnership(tenantId, accountId, ownerType);
            return ownership != null ? ownership.getPercentage() : null;
        }
        TenantOwnershipHistory history = dashboardDao.findHistoricalOwnership(
                tenantId, accountId, ownerType, ownershipMonth.atDay(1));
        return history != null ? history.getPercentage() : null;
    }

    private static BigDecimal earningsFrom(BigDecimal netProfit, BigDecimal percentage) {
        return netProfit
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP);
    }

    private static final class ProfitExpenses {
        private final BigDecimal profit;
        private final BigDecimal expenses;
        private final BigDecimal netProfit;

        private ProfitExpenses(BigDecimal profit, BigDecimal expenses, BigDecimal netProfit) {
            this.profit = profit;
            this.expenses = expenses;
            this.netProfit = netProfit;
        }
    }
}
