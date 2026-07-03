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

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getOwnershipList(
            @RequestParam(value = "tenant_id", required = false) Integer tenantId,
            @RequestParam(value = "group_code", required = false) String groupCode,
            @RequestParam(value = "month", required = false) String month) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            List<TenantOwnershipDTO> list = tenantOwnershipService.getOwnershipList(tenantId, groupCode, month);

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
            @RequestParam(value = "tenant_id", required = false) String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
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
}
