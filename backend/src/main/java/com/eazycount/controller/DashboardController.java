package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.DashboardCurrencyAmountDTO;
import com.eazycount.dto.DashboardGroupCompanyNetProfitDTO;
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

    @GetMapping("/kpi")
    public ResponseEntity<Map<String, Object>> getKpi(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            DashboardKpiDTO kpi = dashboardService.getKpi(tenantId, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", kpi);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // Group 模式 KPI 卡片：group_tenant_id 是 Group 自己的 tenant id，company_tenant_ids 是这个
    // Group 下面有权限查看的子公司 id 列表（不传或传空字符串就当作没有子公司，Group Profit 为 0）——
    // 跟 /kpi-all 一样，公司列表由前端算好传进来，后端不重新判断哪些公司属于这个 Group。
    @GetMapping("/group-kpi")
    public ResponseEntity<Map<String, Object>> getKpiForGroup(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            DashboardKpiDTO kpi = dashboardService.getKpiForGroup(
                    groupTenantId, companyTenantIds, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", kpi);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    // Group-only "Net Profit" tab: each member company's own Net Profit (not weighted by
    // equity %), same params as /group-kpi. Separate on-demand endpoint — only called when
    // that tab is actually visible (groupOnlyDashboard + at least one member company).
    @GetMapping("/group-kpi/net-profit")
    public ResponseEntity<Map<String, Object>> getGroupCompanyNetProfitBreakdown(
            @RequestParam(value = "group_tenant_id", required = true) String groupTenantIdStr,
            @RequestParam(value = "company_tenant_ids", required = false) String companyTenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            List<DashboardGroupCompanyNetProfitDTO> rows = dashboardService.getGroupCompanyNetProfitBreakdown(
                    groupTenantId, companyTenantIds, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", rows);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/kpi-all")
    public ResponseEntity<Map<String, Object>> getKpiForCompanies(
            @RequestParam(value = "tenant_ids", required = true) String tenantIdsStr,
            @RequestParam(value = "date_from", required = true) String dateFromStr,
            @RequestParam(value = "date_to", required = true) String dateToStr,
            @RequestParam(value = "currency", required = true) String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            List<Integer> tenantIds = parseTenantIds(tenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            DashboardKpiDTO kpi = dashboardService.getKpiForCompanies(tenantIds, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", kpi);
            return ResponseEntity.ok(body);
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
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            List<DashboardTrendPointDTO> trend = dashboardService.getTrend(tenantId, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", trend);
            return ResponseEntity.ok(body);
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
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            List<Integer> tenantIds = parseTenantIds(tenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            List<DashboardTrendPointDTO> trend = dashboardService.getTrendForCompanies(tenantIds, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", trend);
            return ResponseEntity.ok(body);
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
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer groupTenantId = resolveTenantId(groupTenantIdStr);
            List<Integer> companyTenantIds = parseTenantIdsAllowEmpty(companyTenantIdsStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            List<DashboardTrendPointDTO> trend = dashboardService.getTrendForGroup(
                    groupTenantId, companyTenantIds, dateFrom, dateTo, currency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", trend);
            return ResponseEntity.ok(body);
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
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            LocalDate dateFrom = LocalDate.parse(dateFromStr.trim());
            LocalDate dateTo = LocalDate.parse(dateToStr.trim());

            List<DashboardCurrencyAmountDTO> rows =
                    dashboardService.getKpiCurrencyBreakdown(tenantId, dateFrom, dateTo, baseCurrency);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", rows);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e.getMessage());
        }
    }

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

    // 跟 parseTenantIds 一样，但空字符串/不传都合法（当作没有子公司），不当错误抛出。
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

    private static ResponseEntity<Map<String, Object>> error(String message) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
