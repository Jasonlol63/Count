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
import java.time.temporal.ChronoUnit;
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

        // Group-level rollup isn't implemented yet — a GROUP tenant gets all-null KPI
        // amounts (frontend renders "-"), same for Earnings.
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

    /**
     * Picks the "previous period" to compare against, aligned the way a human would expect
     * rather than a blind day-count shift whenever the range lines up with calendar boundaries:
     * <ul>
     *   <li>Range = exactly N whole calendar months (starts on the 1st, ends on a month's last
     *       day) — including the N=12 case, which is just "last calendar year" — previous =
     *       the N whole calendar months immediately before it.</li>
     *   <li>Anything else (a partial / arbitrary custom range) — previous = the same number of
     *       days immediately before {@code dateFrom}.</li>
     * </ul>
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
        // expenses is already signed negative (EXPENSES role nets to a debit/outflow), so a
        // plain add gives profit - |expenses|; subtract would double-negate into profit + |expenses|.
        BigDecimal netProfit = profit.add(expenses);
        return new ProfitExpenses(profit, expenses, netProfit);
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

    /** Same ownership lookup as {@link #applyEarnings}, but for the previous period's netProfit only. */
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

    /** "owner" / "user" (PARTNERSHIP admin) / null (no ownership possible for this identity, incl. member logins). */
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

    /** Live table for the current month, monthly snapshot history table otherwise. */
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
