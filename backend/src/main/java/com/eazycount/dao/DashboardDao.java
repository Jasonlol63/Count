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

    // 【Group Profit 用】跟上面的 aggregateWinLossByRole / aggregateCrDrByRole 规则完全一样
    // 唯一区别是这两条会按 tenant_id 分开返回，而不是把所有公司加总成一个数字。
    // 用途：Group 下有好几家子公司，要算 Group Profit 得先知道"每家子公司自己的 Net Profit"，
    // 再乘以各自的股权比例。这两条查询一次性把每家公司各自的 Win/Loss、Cr/Dr 数字都查出来，
    // 不用一家公司一家公司地循环查询（循环查询就是旧系统慢的原因）。
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

    // 【Group Profit 用】查"公司分配给 Group 的股权百分比"，对应 Ownership 页面
    // "Account Ownership" 标签页里那个 "Group: IG" 那一行设置的数字。
    // 例如：A 公司把自己 30% 的股权分给了 IG 这个 Group，那 A 公司的 Net Profit
    // 只有 30% 会算进 IG 的 Group Profit 里。
    // 这条一次性把一个 Group 下所有子公司各自分配的百分比都查出来（一条 SQL 查全部，
    // 不用一家一家查）。跟 findLiveOwnership 一样是查"当前生效"的数字，
    // 如果 Dashboard 看的是过去某个月，则改用下面 findHistoricalGroupEquityPercentages
    // 查历史快照表——这跟 findLiveOwnership / findHistoricalOwnership 的当前值/历史值区分逻辑是一致的。
    List<TenantOwnership> findGroupEquityPercentages(
            @Param("companyTenantIds") List<Integer> companyTenantIds,
            @Param("groupTenantId") Integer groupTenantId);

    // 上面那条的历史版本：查过去某个月的股权分配快照，effectiveMonth 传该月第一天。
    List<TenantOwnershipHistory> findHistoricalGroupEquityPercentages(
            @Param("companyTenantIds") List<Integer> companyTenantIds,
            @Param("groupTenantId") Integer groupTenantId,
            @Param("effectiveMonth") LocalDate effectiveMonth);

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

    // Current (live) ownership row for one shareholder identity on a tenant — Earnings multiplier source.
    TenantOwnership findLiveOwnership(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("ownerType") String ownerType);

    // Historical snapshot ownership row for a past month (effectiveMonth = first day of that month).
    TenantOwnershipHistory findHistoricalOwnership(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("ownerType") String ownerType,
            @Param("effectiveMonth") LocalDate effectiveMonth);

    // 【Trend Chart 的 Earnings 走势线用，Company/Group 通用】一次性批量查"当前登录身份在
    // 这一个 tenant 上"一批历史月份各自的股权%——不是按月份一条一条查，而是一条
    // effective_month IN (...) 查完这批月份。tenantId 可以是公司自己的 id，也可以是 Group
    // 自己的 id（Group Earnings 走势线复用同一个方法，因为 tenant_ownership_history 这张表
    // 本来就不区分 tenant_id 指向的是公司还是 Group）。当月（live）不在这条查询范围内，
    // 还是用现成的 findLiveOwnership 单独查。
    List<TenantOwnershipHistory> findOwnershipPercentagesByMonths(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("ownerType") String ownerType,
            @Param("effectiveMonths") List<LocalDate> effectiveMonths);

    // 【Group Profit 走势线用】findGroupEquityPercentages 的"按月批量"版本——一批子公司、
    // 一批历史月份，一条 effective_month IN (...) 查完，不按月份循环查询。当月（live）
    // 不在这条查询范围内，还是用现成的 findGroupEquityPercentages 单独查。
    List<TenantOwnershipHistory> findGroupEquityPercentagesByMonths(
            @Param("companyTenantIds") List<Integer> companyTenantIds,
            @Param("groupTenantId") Integer groupTenantId,
            @Param("effectiveMonths") List<LocalDate> effectiveMonths);
}
