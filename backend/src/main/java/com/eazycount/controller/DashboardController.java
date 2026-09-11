package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.DashboardCurrencyAmountDTO;
import com.eazycount.dto.DashboardKpiDTO;
import com.eazycount.dto.DashboardTrendPointDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    // Reused only to resolve a "C168"-style tenant code to an id, same helper as TenantOwnershipController.
    @Autowired
    private TenantDao tenantDao;

    // ==================== 单 Company ====================
    @GetMapping("/kpi")
    public ResponseEntity<Map<String, Object>> getKpi(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpi(tenantId, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/chart")
    public ResponseEntity<Map<String, Object>> getTrend(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getTrend(tenantId, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/kpi/currency-breakdown")
    public ResponseEntity<Map<String, Object>> getKpiCurrencyBreakdown(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "base_currency", required = true) String baseCurrency) {
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpiCurrencyBreakdown(tenantId, dateFrom, dateTo, baseCurrency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // ==================== 单 Group ====================
    @GetMapping("/group-kpi")
    public ResponseEntity<Map<String, Object>> getKpiForGroup(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpiForGroup(groupTenantId, companyTenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/chart-group")
    public ResponseEntity<Map<String, Object>> getTrendForGroup(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getTrendForGroup(groupTenantId, companyTenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/group-kpi/currency-breakdown")
    public ResponseEntity<Map<String, Object>> getGroupKpiCurrencyBreakdown(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "base_currency", required = true) String baseCurrency) {
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getGroupKpiCurrencyBreakdown(
                    groupTenantId, companyTenantIds, dateFrom, dateTo, baseCurrency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/group-kpi/net-profit")
    public ResponseEntity<Map<String, Object>> getGroupCompanyNetProfitBreakdown(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getGroupCompanyNetProfitBreakdown(
                    groupTenantId, companyTenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // ==================== Group: All ====================
    @GetMapping("/kpi-all-groups")
    public ResponseEntity<Map<String, Object>> getKpiForGroups(
            @RequestParam(value = "group_tenant_ids", required = true) String groupTenantIdsStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            List<Integer> groupTenantIds = parseGroupTenantIds(groupTenantIdsStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpiForGroups(groupTenantIds, companyTenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/chart-all-groups")
    public ResponseEntity<Map<String, Object>> getTrendForGroups(
            @RequestParam(value = "group_tenant_ids", required = true) String groupTenantIdsStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            List<Integer> groupTenantIds = parseGroupTenantIds(groupTenantIdsStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getTrendForGroups(groupTenantIds, companyTenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/kpi-all-groups/currency-breakdown")
    public ResponseEntity<Map<String, Object>> getGroupsKpiCurrencyBreakdown(
            @RequestParam(value = "group_tenant_ids", required = true) String groupTenantIdsStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "base_currency", required = true) String baseCurrency) {
        try {
            List<Integer> groupTenantIds = parseGroupTenantIds(groupTenantIdsStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getGroupsKpiCurrencyBreakdown(
                    groupTenantIds, companyTenantIds, dateFrom, dateTo, baseCurrency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // ==================== Company: All ====================

    @GetMapping("/kpi-all")
    public ResponseEntity<Map<String, Object>> getKpiForCompanies(
            @RequestParam(value = "tenant_ids", required = true) String tenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            List<Integer> tenantIds = parseTenantIds(tenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpiForCompanies(tenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/chart-all")
    public ResponseEntity<Map<String, Object>> getTrendForCompanies(
            @RequestParam(value = "tenant_ids", required = true) String tenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        try {
            List<Integer> tenantIds = parseTenantIds(tenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getTrendForCompanies(tenantIds, dateFrom, dateTo, currency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/kpi-all/currency-breakdown")
    public ResponseEntity<Map<String, Object>> getKpiCurrencyBreakdownForCompanies(
            @RequestParam(value = "tenant_ids", required = true) String tenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "base_currency", required = true) String baseCurrency) {
        try {
            List<Integer> tenantIds = parseTenantIds(tenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());
            return ok(dashboardService.getKpiCurrencyBreakdownForCompanies(tenantIds, dateFrom, dateTo, baseCurrency));
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // ==================== 共享辅助方法 ====================

    private Integer resolveTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new BusinessException("tenant_id is required");
        }
        try {
            return Integer.valueOf(tenantIdStr.trim());
        } catch (NumberFormatException e) {
            Tenant tenant = tenantDao.findTenantByCode(tenantIdStr.trim());
            if (tenant != null) {
                return tenant.getId();
            } else {
                throw new BusinessException("Tenant '" + tenantIdStr + "' not found");
            }
        }
    }

    private static List<Integer> parseTenantIds(String tenantIdsStr) {
        if (tenantIdsStr == null || tenantIdsStr.isBlank()) {
            throw new BusinessException("tenant_ids is required");
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : tenantIdsStr.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid tenant id in tenant_ids: '" + trimmed + "'");
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException("tenant_ids is required");
        }
        return ids;
    }

    private static List<Integer> parseGroupTenantIds(String groupTenantIdsStr) {
        if (groupTenantIdsStr == null || groupTenantIdsStr.isBlank()) {
            throw new BusinessException("group_tenant_ids is required");
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : groupTenantIdsStr.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid tenant id in group_tenant_ids: '" + trimmed + "'");
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException("group_tenant_ids is required");
        }
        return ids;
    }

    private static List<Integer> parseTenantIdsAllowEmpty(String tenantIdsStr) {
        if (tenantIdsStr == null || tenantIdsStr.isBlank()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : tenantIdsStr.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid tenant id in company_tenant_ids: '" + trimmed + "'");
            }
        }
        return ids;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("success", true);
        body.put("message", "");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> error(String message) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
