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
    List<DashboardKpiDTO.RoleAmount> aggregateWinLossByRole(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardKpiDTO.RoleAmount> aggregateCrDrByRole(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    // Same Win/Loss bucket as aggregateWinLossByRole and Cr/Dr bucket as aggregateCrDrByRole (CLEAR still excluded),
    // grouped by transaction_date as well as role — feeds the Trend Chart.
    List<DashboardTrendPointDTO.RoleAmount> aggregateWinLossByRoleAndDate(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("roles") List<String> roles,
            @Param("currencyCode") String currencyCode);

    List<DashboardTrendPointDTO.RoleAmount> aggregateCrDrByRoleAndDate(
            @Param("tenantId") Integer tenantId,
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
}
