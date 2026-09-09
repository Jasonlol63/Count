package com.eazycount.service;

import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;

import java.time.LocalDate;
import java.util.List;

public interface DashboardService {

    // KPI cards for one tenant/currency over [dateFrom, dateTo], plus the previous period.
    DashboardKpiDTO getKpi(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    //Trend Chart use, same rules as getKpi, one point per day in [dateFrom, dateTo].
    List<DashboardTrendPointDTO> getTrend(Integer tenantId, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // "Company: All" rollup — Profit/Expenses/NetProfit summed across every given tenant, one currency. No Earnings, no previous-period. Caller resolves which tenantIds are in scope.
    DashboardKpiDTO getKpiForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    //"Company: All" trend Chart use - Profit/Expenses/NetProfit summed across every given tenant, one currency.
    List<DashboardTrendPointDTO> getTrendForCompanies(List<Integer> tenantIds, LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group 模式的 KPI 卡片，算法跟 Company 模式完全不一样：
    // - Group Profit = 把这个 Group 名下每家子公司「自己的 Net Profit」乘以「该公司分配给这个
    //   Group 的股权百分比」，再全部加起来（在 Ownership 页面 "Account Ownership" 标签页设置）。
    //   哪家公司分配了 0%，就贡献 0，不算进去。
    // - Group Expenses = 直接从 Group 自己的流水（Group 这个 tenant 自己名下的 transactions）
    //   里取，跟 Company 模式算 Expenses 的规则完全一样，只是查的 tenant_id 换成 Group 自己的 id。
    // - Group Net Profit = Group Profit + Group Expenses（Expenses 本身是负数，所以是加）。
    // - Group Earnings = Group Net Profit 乘以当前登录身份在这个 Group 里的持股百分比
    //   （在 Ownership 页面 "Group Earnings" 标签页设置，对应 tenant_ownership 表里
    //   tenant_id = 这个 Group 自己的 id 的那一行）。
    // companyTenantIds：这个 Group 当前分组下有哪些子公司，由前端算好后传进来（跟 getKpiForCompanies
    // 一样，后端不自己去重新判断哪些公司属于这个 Group，避免逻辑跟前端权限过滤对不上）。
    DashboardKpiDTO getKpiForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                    LocalDate dateFrom, LocalDate dateTo, String currencyCode);

    // Group 模式的 Trend Chart：跟 getKpiForGroup 同一套 Group Profit/Expenses/NetProfit 算法，
    // 只是从"整个区间一个总数"变成"每一天一个数"。Earnings 这条线（Company 模式的 getTrend 也一样）
    // 这次改成按"每一天所在的月份"分别查股权%，不是整个区间用查询末尾那一天的一个百分比顶到底——
    // 哪个月没配置过股权，那个月就当 0% 处理。
    List<DashboardTrendPointDTO> getTrendForGroup(Integer groupTenantId, List<Integer> companyTenantIds,
                                                   LocalDate dateFrom, LocalDate dateTo, String currencyCode);
}
