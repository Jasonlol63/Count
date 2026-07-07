package com.eazycount.controller;

import com.eazycount.dto.TenantOwnershipDTO;
import com.eazycount.service.TenantOwnershipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ownership")
public class TenantOwnershipController {

    @Autowired
    private TenantOwnershipService tenantOwnershipService;

    private Integer resolveTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalArgumentException("tenant_id is required");
        }
        try {
            return Integer.valueOf(tenantIdStr.trim());
        } catch (NumberFormatException e) {
            com.eazycount.entity.Tenant tenant = tenantOwnershipService.findTenantByCode(tenantIdStr.trim());
            if (tenant != null) {
                return tenant.getId();
            } else {
                throw new IllegalArgumentException("Tenant '" + tenantIdStr + "' not found");
            }
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getOwnershipList(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr,
            @RequestParam(value = "month", required = false) String month) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            List<TenantOwnershipDTO> list = tenantOwnershipService.getOwnershipList(tenantId, month);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "");
            body.put("data", list);

            Map<String, Object> meta = new LinkedHashMap<>();
            boolean isHistorical = month != null && !month.isBlank() && !tenantOwnershipService.isCurrentMonth(month);
            meta.put("is_historical", isHistorical);
            meta.put("effective_month", month);
            body.put("meta", meta);

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", List.of());
            return ResponseEntity.ok(body);
        }
    }

    @GetMapping("/available-accounts")
    public ResponseEntity<Map<String, Object>> getShareholderCandidates(
            @RequestParam(value = "tenant_id", required = true) String tenantIdStr) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer tenantId = resolveTenantId(tenantIdStr);
            List<TenantOwnershipDTO> list = tenantOwnershipService.getShareholderCandidates(tenantId);
            body.put("status", "success");
            body.put("success", true);
            body.put("message", "Account Option retrieved successfully");
            body.put("data", list);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", List.of());
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/link-partner")
    public ResponseEntity<Map<String, Object>> linkpartner(@RequestBody Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String tenantIdStr = payload.get("tenant_id") != null ? payload.get("tenant_id").toString() : null;
            Integer tenantId = resolveTenantId(tenantIdStr);

            String loginId = payload.get("login_id") != null ? payload.get("login_id").toString() : null;
            String forceType = payload.get("force_type") != null ? payload.get("force_type").toString() : "";

            // 直接调用包含校验与解析的 linkPartner 业务逻辑
            Map<String, Object> result = tenantOwnershipService.linkPartner(tenantId, loginId, forceType);
            body.putAll(result);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", List.of());
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("batch-save-ownership")
    public ResponseEntity<Map<String, Object>> batchSaveOwnership(@RequestBody Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String tenantIdStr = payload.get("tenant_id") != null ? payload.get("tenant_id").toString() : null;
            Integer tenantId = resolveTenantId(tenantIdStr);

            // 注解: 作用是告诉编译器：“我知道这里有风险，但请不要弹警告（Warning）
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> owners = (List<Map<String, Object>>) payload.get("owners");
            if (owners == null) {
                owners = List.of();
            }

            String month = payload.get("month") != null ? payload.get("month").toString() : null;

            @SuppressWarnings("unchecked")
            List<String> retrofillMonths = (List<String>) payload.get("retrofill_months");

            tenantOwnershipService.saveOwnership(tenantId, owners, month, retrofillMonths);

            body.put("status", "success");
            body.put("success", true);
            body.put("message", "Ownership inserted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", List.of());
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update-parent-tenant")
    public ResponseEntity<Map<String, Object>> updateParentTenant(@RequestBody Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        try{
            String tenantIdStr = payload.get("tenant_id") != null ? payload.get("tenant_id").toString() : null;
            Integer tenantId = resolveTenantId(tenantIdStr);
            String parentCode = payload.get("parent_code") != null ? payload.get("parent_code").toString() : null;
            tenantOwnershipService.updateTenantParentId(tenantId, parentCode);

            boolean cleared = parentCode == null || parentCode.isBlank();
            body.put("status", "success");
            body.put("success", true);
            body.put("message", cleared ? "Parent Tenant cleared successfully" : "Parent Tenant updated successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
        catch (Exception e) {
            body.put("status", "error");
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", List.of());
            return ResponseEntity.ok(body);
        }
    }
}
