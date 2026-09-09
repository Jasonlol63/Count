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

        List<Integer> tenantIds = List.of(tenantId);
        ProfitExpenses current = computeProfitExpenses(tenantIds, dateFrom, dateTo, currency);
        dto.setProfit(current.profit);
        dto.setExpenses(current.expenses);
        dto.setNetProfit(current.netProfit);
        applyEarnings(dto, tenantId, dateTo, current.netProfit);

        LocalDate[] previousRange = resolvePreviousRange(dateFrom, dateTo);
        LocalDate previousDateFrom = previousRange[0];
        LocalDate previousDateTo = previousRange[1];
        dto.setPreviousDateFrom(previousDateFrom);
        dto.setPreviousDateTo(previousDateTo);

        ProfitExpenses previous = computeProfitExpenses(tenantIds, previousDateFrom, previousDateTo, currency);
        dto.setPreviousProfit(previous.profit);
        dto.setPreviousExpenses(previous.expenses);
        dto.setPreviousNetProfit(previous.netProfit);
        if (dto.isShowEarnings()) {
            dto.setPreviousEarnings(resolveEarningsAmount(tenantId, previousDateTo, previous.netProfit));
        }

        return dto;
    }

    @Override
    public DashboardKpiDTO getKpiForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo,
                                               String currencyCode) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new BusinessException("tenant_ids is required");
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

        // "Company: All" rollup — which tenant ids belong in scope (e.g. every company under the
        // selected Group tab) is resolved by the caller, not here; see DashboardDao#aggregateWinLossByRole.
        // No Earnings, no previous-period comparison for this view (not requested — those would each
        // mean a second N-tenant query, and Earnings has no defined meaning for a multi-company sum).
        ProfitExpenses totals = computeProfitExpenses(tenantIds, dateFrom, dateTo, currencyCode.trim());
        DashboardKpiDTO dto = new DashboardKpiDTO();
        dto.setProfit(totals.profit);
        dto.setExpenses(totals.expenses);
        dto.setNetProfit(totals.netProfit);
        dto.setShowEarnings(false);
        return dto;
    }

    @Override
    public DashboardKpiDTO getKpiForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                           LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        if (groupTenantId == null) {
            throw new BusinessException("group_tenant_id is required");
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
        // No company list from the frontend → treat as "no member companies", Group Profit is 0 (not an error).
        List<Integer> companies = companyTenantIds == null ? List.of() : companyTenantIds;

        Tenant tenant = tenantDao.findTenantById(groupTenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }
        if (tenant.getTenantType() != Tenant.TenantType.GROUP) {
            throw new BusinessException("Tenant is not a Group");
        }

        DashboardKpiDTO dto = new DashboardKpiDTO();

        ProfitExpenses current = computeGroupKpi(groupTenantId, companies, dateFrom, dateTo, currency);
        dto.setProfit(current.profit);
        dto.setExpenses(current.expenses);
        dto.setNetProfit(current.netProfit);
        // Group Earnings reuses applyEarnings as-is: it just looks up the tenant_ownership row
        // for tenant_id = groupTenantId and multiplies by Group Net Profit — no new code needed.
        applyEarnings(dto, groupTenantId, dateTo, current.netProfit);

        LocalDate[] previousRange = resolvePreviousRange(dateFrom, dateTo);
        LocalDate previousDateFrom = previousRange[0];
        LocalDate previousDateTo = previousRange[1];
        dto.setPreviousDateFrom(previousDateFrom);
        dto.setPreviousDateTo(previousDateTo);

        ProfitExpenses previous = computeGroupKpi(groupTenantId, companies, previousDateFrom, previousDateTo, currency);
        dto.setPreviousProfit(previous.profit);
        dto.setPreviousExpenses(previous.expenses);
        dto.setPreviousNetProfit(previous.netProfit);
        if (dto.isShowEarnings()) {
            dto.setPreviousEarnings(resolveEarningsAmount(groupTenantId, previousDateTo, previous.netProfit));
        }

        return dto;
    }

    /* Group Profit (weighted rollup of member companies) + Group Expenses (Group's own ledger). */
    private ProfitExpenses computeGroupKpi(Integer groupTenantId, List<Integer> companyTenantIds,
                                            LocalDate dateFrom, LocalDate dateTo, String currency) {
        BigDecimal groupProfit = computeGroupProfit(companyTenantIds, groupTenantId, dateFrom, dateTo, currency);
        // Only take Expenses from the Group's own ledger — even if it happens to have PROFIT-role
        // rows too, those don't count; Group Profit can only come from the member-company rollup.
        // This is a business rule, not an oversight.
        BigDecimal groupExpenses = computeProfitExpenses(List.of(groupTenantId), dateFrom, dateTo, currency).expenses;
        BigDecimal groupNetProfit = groupProfit.add(groupExpenses);
        return new ProfitExpenses(groupProfit, groupExpenses, groupNetProfit);
    }

    /*
     * Group Profit = sum over every member company of (its own Net Profit × its equity % in
     * this Group). Net Profits and equity % are each fetched in one batch query, then joined
     * and weighted in plain Java — not a third SQL query.
     */
    private BigDecimal computeGroupProfit(List<Integer> companyTenantIds, Integer groupTenantId,
                                           LocalDate dateFrom, LocalDate dateTo, String currency) {
        if (companyTenantIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<Integer, Map<String, BigDecimal>> winLossByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateWinLossByRoleAndTenant(companyTenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, Map<String, BigDecimal>> crDrByTenantRole = toTenantRoleMap(
                dashboardDao.aggregateCrDrByRoleAndTenant(companyTenantIds, dateFrom, dateTo, roles, currency));
        Map<Integer, BigDecimal> equityPercentageByTenant =
                findGroupEquityPercentages(companyTenantIds, groupTenantId, dateTo);

        BigDecimal groupProfit = BigDecimal.ZERO;
        for (Integer tenantId : companyTenantIds) {
            BigDecimal percentage = equityPercentageByTenant.get(tenantId);
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) == 0) {
                continue; // This company didn't allocate any equity to the Group — contributes 0, skip.
            }
            Map<String, BigDecimal> winLossByRole = winLossByTenantRole.getOrDefault(tenantId, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByTenantRole.getOrDefault(tenantId, Map.of());
            BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal companyNetProfit = companyProfit.add(companyExpenses);

            groupProfit = groupProfit.add(companyNetProfit
                    .multiply(percentage)
                    .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
        }
        return groupProfit;
    }

    /* Live table for the current month, snapshot history table otherwise — same rule as findOwnershipPercentage. */
    private Map<Integer, BigDecimal> findGroupEquityPercentages(List<Integer> companyTenantIds,
                                                                  Integer groupTenantId, LocalDate dateTo) {
        Map<Integer, BigDecimal> percentageByTenant = new HashMap<>();
        YearMonth ownershipMonth = YearMonth.from(dateTo);
        if (ownershipMonth.equals(YearMonth.now())) {
            for (TenantOwnership row : dashboardDao.findGroupEquityPercentages(companyTenantIds, groupTenantId)) {
                percentageByTenant.put(row.getTenantId(), row.getPercentage());
            }
        } else {
            for (TenantOwnershipHistory row : dashboardDao.findHistoricalGroupEquityPercentages(
                    companyTenantIds, groupTenantId, ownershipMonth.atDay(1))) {
                percentageByTenant.put(row.getTenantId(), row.getPercentage());
            }
        }
        return percentageByTenant;
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
        List<Integer> tenantIds = List.of(tenantId);
        Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));

        List<DashboardTrendPointDTO> points = buildTrendPoints(dateFrom, dateTo, winLossByDateRole, crDrByDateRole);

        // Earnings line: if the identity has no ownership standing at all (member/ledger login),
        // leave the whole line null and skip this query.
        String ownerType = resolveOwnerType();
        if (ownerType != null) {
            Integer accountId = SecurityUtils.currentUser().user_id;
            Map<YearMonth, BigDecimal> percentageByMonth =
                    resolveOwnershipPercentagesByMonth(tenantId, accountId, ownerType, dateFrom, dateTo);
            applyTrendEarnings(points, percentageByMonth);
        }

        return points;
    }

    @Override
    public List<DashboardTrendPointDTO> getTrendForCompanies(List<Integer> tenantIds, LocalDate dateFrom,
                                                               LocalDate dateTo, String currencyCode) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new BusinessException("tenant_ids is required");
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

        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(tenantIds, dateFrom, dateTo, roles, currency));

        return buildTrendPoints(dateFrom, dateTo, winLossByDateRole, crDrByDateRole);
    }

    @Override
    public List<DashboardTrendPointDTO> getTrendForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                                           LocalDate dateFrom, LocalDate dateTo, String currencyCode) {
        if (groupTenantId == null) {
            throw new BusinessException("group_tenant_id is required");
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
        List<Integer> companies = companyTenantIds == null ? List.of() : companyTenantIds;

        Tenant tenant = tenantDao.findTenantById(groupTenantId);
        if (tenant == null) {
            throw new BusinessException("Tenant not found");
        }
        if (tenant.getTenantType() != Tenant.TenantType.GROUP) {
            throw new BusinessException("Tenant is not a Group");
        }

        List<DashboardTrendPointDTO> points = buildGroupTrendPoints(groupTenantId, companies, dateFrom, dateTo, currency);

        String ownerType = resolveOwnerType();
        if (ownerType != null) {
            Integer accountId = SecurityUtils.currentUser().user_id;
            Map<YearMonth, BigDecimal> percentageByMonth =
                    resolveOwnershipPercentagesByMonth(groupTenantId, accountId, ownerType, dateFrom, dateTo);
            applyTrendEarnings(points, percentageByMonth);
        }

        return points;
    }

    /*
     * Group Trend Chart: same algorithm as computeGroupKpi(), just one point per day instead
     * of one total. Member companies' daily Win/Loss+Cr/Dr come from the new *AndTenantAndDate
     * queries; equity % is batched per month (resolveGroupEquityPercentagesByMonth), not one
     * flat percentage for the whole range; the Group's own daily Expenses reuse the existing
     * single-tenant day queries.
     */
    private List<DashboardTrendPointDTO> buildGroupTrendPoints(Integer groupTenantId, List<Integer> companyTenantIds,
                                                                LocalDate dateFrom, LocalDate dateTo, String currency) {
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);

        List<Integer> groupTenantIds = List.of(groupTenantId);
        Map<LocalDate, Map<String, BigDecimal>> groupWinLossByDateRole = toDateRoleMap(
                dashboardDao.aggregateWinLossByRoleAndDate(groupTenantIds, dateFrom, dateTo, roles, currency));
        Map<LocalDate, Map<String, BigDecimal>> groupCrDrByDateRole = toDateRoleMap(
                dashboardDao.aggregateCrDrByRoleAndDate(groupTenantIds, dateFrom, dateTo, roles, currency));

        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> companyWinLossByTenantDateRole = Map.of();
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> companyCrDrByTenantDateRole = Map.of();
        Map<YearMonth, Map<Integer, BigDecimal>> equityPercentageByMonth = Map.of();
        if (!companyTenantIds.isEmpty()) {
            companyWinLossByTenantDateRole = toTenantDateRoleMap(
                    dashboardDao.aggregateWinLossByRoleAndTenantAndDate(companyTenantIds, dateFrom, dateTo, roles, currency));
            companyCrDrByTenantDateRole = toTenantDateRoleMap(
                    dashboardDao.aggregateCrDrByRoleAndTenantAndDate(companyTenantIds, dateFrom, dateTo, roles, currency));
            equityPercentageByMonth = resolveGroupEquityPercentagesByMonth(companyTenantIds, groupTenantId, dateFrom, dateTo);
        }

        List<DashboardTrendPointDTO> points = new ArrayList<>();
        for (LocalDate date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            Map<Integer, BigDecimal> percentageByTenant =
                    equityPercentageByMonth.getOrDefault(YearMonth.from(date), Map.of());

            BigDecimal groupProfit = BigDecimal.ZERO;
            for (Integer tenantId : companyTenantIds) {
                BigDecimal percentage = percentageByTenant.get(tenantId);
                if (percentage == null || percentage.compareTo(BigDecimal.ZERO) == 0) {
                    continue; // No equity allocated to the Group this month (or never configured) — contributes 0.
                }
                Map<String, BigDecimal> winLossByRole = companyWinLossByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(date, Map.of());
                Map<String, BigDecimal> crDrByRole = companyCrDrByTenantDateRole
                        .getOrDefault(tenantId, Map.of()).getOrDefault(date, Map.of());
                BigDecimal companyProfit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
                BigDecimal companyExpenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
                BigDecimal companyNetProfit = companyProfit.add(companyExpenses);

                groupProfit = groupProfit.add(companyNetProfit
                        .multiply(percentage)
                        .divide(BigDecimal.valueOf(100), EARNINGS_SCALE, RoundingMode.HALF_UP));
            }

            Map<String, BigDecimal> groupWinLossByRole = groupWinLossByDateRole.getOrDefault(date, Map.of());
            Map<String, BigDecimal> groupCrDrByRole = groupCrDrByDateRole.getOrDefault(date, Map.of());
            BigDecimal groupExpenses = amountForRole(ROLE_EXPENSES, groupWinLossByRole, groupCrDrByRole);
            BigDecimal groupNetProfit = groupProfit.add(groupExpenses);

            points.add(new DashboardTrendPointDTO(date, groupProfit, groupExpenses, groupNetProfit, null));
        }
        return points;
    }

    private static Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> toTenantDateRoleMap(
            List<DashboardTrendPointDTO.RoleAmount> rows) {
        Map<Integer, Map<LocalDate, Map<String, BigDecimal>>> byTenant = new HashMap<>();
        for (DashboardTrendPointDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byTenant.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                    .computeIfAbsent(row.getDate(), d -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byTenant;
    }

    /*
     * For the Trend Chart's Earnings line: one identity, one tenant (company or Group), batch
     * fetch each month's own ownership % across the range — not one flat percentage for the
     * whole range. Current month goes through findLiveOwnership (live table); every other
     * month is fetched in one findOwnershipPercentagesByMonths call (effective_month IN (...)),
     * not one query per month.
     */
    private Map<YearMonth, BigDecimal> resolveOwnershipPercentagesByMonth(Integer tenantId, Integer accountId,
                                                                           String ownerType, LocalDate dateFrom, LocalDate dateTo) {
        Map<YearMonth, BigDecimal> percentageByMonth = new HashMap<>();
        YearMonth currentMonth = YearMonth.now();
        List<LocalDate> historyMonths = new ArrayList<>();
        for (YearMonth month = YearMonth.from(dateFrom); !month.isAfter(YearMonth.from(dateTo)); month = month.plusMonths(1)) {
            if (!month.equals(currentMonth)) {
                historyMonths.add(month.atDay(1));
            }
        }
        if (!historyMonths.isEmpty()) {
            for (TenantOwnershipHistory row : dashboardDao.findOwnershipPercentagesByMonths(
                    tenantId, accountId, ownerType, historyMonths)) {
                percentageByMonth.put(YearMonth.from(row.getEffectiveMonth()), row.getPercentage());
            }
        }
        if (!YearMonth.from(dateFrom).isAfter(currentMonth) && !currentMonth.isAfter(YearMonth.from(dateTo))) {
            TenantOwnership live = dashboardDao.findLiveOwnership(tenantId, accountId, ownerType);
            if (live != null) {
                percentageByMonth.put(currentMonth, live.getPercentage());
            }
        }
        return percentageByMonth;
    }

    /* Group Profit Trend Chart: same pattern as above, "one identity" swapped for "a batch of member companies". */
    private Map<YearMonth, Map<Integer, BigDecimal>> resolveGroupEquityPercentagesByMonth(
            List<Integer> companyTenantIds, Integer groupTenantId, LocalDate dateFrom, LocalDate dateTo) {
        Map<YearMonth, Map<Integer, BigDecimal>> percentageByMonth = new HashMap<>();
        YearMonth currentMonth = YearMonth.now();
        List<LocalDate> historyMonths = new ArrayList<>();
        for (YearMonth month = YearMonth.from(dateFrom); !month.isAfter(YearMonth.from(dateTo)); month = month.plusMonths(1)) {
            if (!month.equals(currentMonth)) {
                historyMonths.add(month.atDay(1));
            }
        }
        if (!historyMonths.isEmpty()) {
            for (TenantOwnershipHistory row : dashboardDao.findGroupEquityPercentagesByMonths(
                    companyTenantIds, groupTenantId, historyMonths)) {
                percentageByMonth.computeIfAbsent(YearMonth.from(row.getEffectiveMonth()), m -> new HashMap<>())
                        .put(row.getTenantId(), row.getPercentage());
            }
        }
        if (!YearMonth.from(dateFrom).isAfter(currentMonth) && !currentMonth.isAfter(YearMonth.from(dateTo))) {
            Map<Integer, BigDecimal> liveByTenant = new HashMap<>();
            for (TenantOwnership row : dashboardDao.findGroupEquityPercentages(companyTenantIds, groupTenantId)) {
                liveByTenant.put(row.getTenantId(), row.getPercentage());
            }
            percentageByMonth.put(currentMonth, liveByTenant);
        }
        return percentageByMonth;
    }

    // A month missing from `percentageByMonth` (never configured) counts as 0% (earnings=0, not null).
    private static void applyTrendEarnings(List<DashboardTrendPointDTO> points, Map<YearMonth, BigDecimal> percentageByMonth) {
        for (DashboardTrendPointDTO point : points) {
            BigDecimal percentage = percentageByMonth.getOrDefault(YearMonth.from(point.getDate()), BigDecimal.ZERO);
            point.setEarnings(earningsFrom(point.getNetProfit(), percentage));
        }
    }

    /** One point per day in [dateFrom, dateTo]; a day missing from both maps still comes back as zero. */
    private static List<DashboardTrendPointDTO> buildTrendPoints(LocalDate dateFrom, LocalDate dateTo,
            Map<LocalDate, Map<String, BigDecimal>> winLossByDateRole, Map<LocalDate, Map<String, BigDecimal>> crDrByDateRole) {
        List<DashboardTrendPointDTO> points = new ArrayList<>();
        for (LocalDate date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            Map<String, BigDecimal> winLossByRole = winLossByDateRole.getOrDefault(date, Map.of());
            Map<String, BigDecimal> crDrByRole = crDrByDateRole.getOrDefault(date, Map.of());

            BigDecimal profit = amountForRole(ROLE_PROFIT, winLossByRole, crDrByRole);
            BigDecimal expenses = amountForRole(ROLE_EXPENSES, winLossByRole, crDrByRole);
            BigDecimal netProfit = profit.add(expenses);

            points.add(new DashboardTrendPointDTO(date, profit, expenses, netProfit, null));
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

    private static Map<Integer, Map<String, BigDecimal>> toTenantRoleMap(List<DashboardKpiDTO.RoleAmount> rows) {
        Map<Integer, Map<String, BigDecimal>> byTenant = new HashMap<>();
        for (DashboardKpiDTO.RoleAmount row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().trim().toUpperCase();
            byTenant.computeIfAbsent(row.getTenantId(), t -> new HashMap<>())
                    .merge(role, row.getAmount(), BigDecimal::add);
        }
        return byTenant;
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

    private ProfitExpenses computeProfitExpenses(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo,
                                                  String currency) {
        List<String> roles = List.of(ROLE_PROFIT, ROLE_EXPENSES);
        Map<String, BigDecimal> winLossByRole = toRoleMap(
                dashboardDao.aggregateWinLossByRole(tenantIds, dateFrom, dateTo, roles, currency));
        Map<String, BigDecimal> crDrByRole = toRoleMap(
                dashboardDao.aggregateCrDrByRole(tenantIds, dateFrom, dateTo, roles, currency));

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
