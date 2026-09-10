package com.eazycount.dao;

import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;
import com.eazycount.entity.TenantOwnership;
import com.eazycount.entity.TenantOwnershipHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/* Dashboard KPI cards (Profit / Expenses / Net Profit / Earnings) for DashboardServiceImpl. */
@Mapper
public interface DashboardDao {

    // Win/Loss bucket per account.role: WIN(+)/LOSE(-)/ADJUSTMENT(as stored) on account_id,
    // plus manual PROFIT-type transfers (To -, From +).
    //----------------------------------------------------------------------------------------------------
    // Cr/Dr bucket per account.role: PAYMENT/CLAIM/CONTRA/RATE(main leg only), To(-)/From(+).
    // CLEAR is intentionally excluded — Dashboard KPI never counts CLEAR for PROFIT/EXPENSES.
    //----------------------------------------------------------------------------------------------------
    // currencyCode scopes to one currency — amounts in different currencies must never be summed together.
    // tenantIds: one entry for a single-company KPI, several entries for the "Company: All" rollup —
    // same SQL, tenant_id IN (...) instead of tenant_id = ?, summed straight in the DB either way.
    List<DashboardKpiDTO.RoleAmount> aggregateWinLossByRole(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardKpiDTO.RoleAmount> aggregateCrDrByRole(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    // Currency Card Use - This work same as the `aggregateWinLossByRole` and `aggregateCrDrByRole` rules.
    // The only difference is that it does not filter by `currencyCode`; instead, it returns one SQL query per currency,
    // allowing you to retrieve the Win/Loss and Cr/Dr figures for each currency under a tenant in a single request,
    // rather than having to send a separate request for each currency.
    List<DashboardKpiDTO.RoleAmount> aggregateWinLossByRoleAndCurrency(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles);

    List<DashboardKpiDTO.RoleAmount> aggregateCrDrByRoleAndCurrency(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles);

    // Group Profit Use - These work same as the aggregateWinLossByRole and aggregateCrDrByRole rules above.
    // The difference is that these two queries return results separately by tenant_id, rather than aggregating all companies into a single figure.
    // Purpose: After calculating the net profit amounts for several subsidiaries under a Group, multiply them by their respective equity ratios.
    // These two queries retrieve the figures for each company in a single pass, eliminating the need for iterative queries.
    List<DashboardKpiDTO.RoleAmount> aggregateWinLossByRoleAndTenant(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardKpiDTO.RoleAmount> aggregateCrDrByRoleAndTenant(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    // 【Group Currency 卡片用】跟 aggregateWinLossByRoleAndTenant / aggregateCrDrByRoleAndTenant 规则
    // 完全一样，只是再去掉币种过滤、同时按 tenant_id 和 currency 两个维度分组——一条 SQL 拿到 Group
    // 下每家子公司在每个币种各自的 Win/Loss、Cr/Dr 数字，用来算"这个 Group 在某个币种上的 Net Profit"
    // （每家子公司该币种的 NetProfit × 股权% 加总），不用为每个币种或每家公司单独查询。
    List<DashboardKpiDTO.RoleAmount> aggregateWinLossByRoleAndTenantAndCurrency(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles);

    List<DashboardKpiDTO.RoleAmount> aggregateCrDrByRoleAndTenantAndCurrency(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles);

    // Trend Chart Use - Same Win/Loss bucket and Cr/Dr bucket above two service (CLEAR still excluded),
    // grouped by transaction_date as well as role — feeds the Trend Chart. Same tenantIds
    // generalization as aggregateWinLossByRole (single id or several for "Company: All").
    List<DashboardTrendPointDTO.RoleAmount> aggregateWinLossByRoleAndDate(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardTrendPointDTO.RoleAmount> aggregateCrDrByRoleAndDate(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    // 【Group Trend Chart 用】跟 aggregateWinLossByRoleAndTenant / aggregateCrDrByRoleAndTenant
    // 规则完全一样，只是再多按 transaction_date 分一层组——用来知道"每家子公司每一天自己赚了
    // 多少"，喂给 Group Profit 那条走势线（每天的子公司 NetProfit × 当月股权% 加总）。
    List<DashboardTrendPointDTO.RoleAmount> aggregateWinLossByRoleAndTenantAndDate(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardTrendPointDTO.RoleAmount> aggregateCrDrByRoleAndTenantAndDate(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    // Direct ownership rows (owner_type IN ('owner','user')) for a batch of tenants — a single
    // tenant is just a one-element list, so this also serves every "one identity, one tenant"
    // call site (no dedicated single-tenant query needed). Live table only; see
    // findOwnershipPercentagesForTenantsAndMonths for historical months.
    List<TenantOwnership> findLiveOwnershipForTenants(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("accountId") Integer accountId,
            @Param("ownerType") String ownerType);

    // Historical snapshot version of the above — a batch of tenants x a batch of months in one
    // query (tenant_id IN (...) AND effective_month IN (...)). A single tenant and/or a single
    // month are just one-element lists, so this also serves every narrower historical lookup.
    List<TenantOwnershipHistory> findOwnershipPercentagesForTenantsAndMonths(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("accountId") Integer accountId,
            @Param("ownerType") String ownerType,
            @Param("effectiveMonths") List<LocalDate> effectiveMonths);

    // Group-allocation rows (owner_type='group', no partner_tenant_id filter — a company
    // allocates to at most one Group, so "which Group did this company allocate to" and "did
    // this company allocate to *this* Group" are answered by the same row; the latter just
    // filters the result in Java) for a batch of tenants. Live table only; see
    // findCompanyGroupAllocationsForTenantsAndMonths for historical months.
    List<TenantOwnership> findCompanyGroupAllocationsForTenants(
            @Param("tenantIds") List<Integer> tenantIds);

    // Historical snapshot version of the above — a batch of tenants x a batch of months in one
    // query, same "single tenant/month is a one-element list" reuse as
    // findOwnershipPercentagesForTenantsAndMonths.
    List<TenantOwnershipHistory> findCompanyGroupAllocationsForTenantsAndMonths(
            @Param("tenantIds") List<Integer> tenantIds,
            @Param("effectiveMonths") List<LocalDate> effectiveMonths);
}
