package com.eazycount.controller;

import com.eazycount.service.AutoRenewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auto-renew")
public class AutoRenewController {

    @Autowired
    private AutoRenewService autoRenewService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestBody Map<String, String> requestParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String status = requestParams.getOrDefault("status", "pending");
            String entityType = requestParams.getOrDefault("entity_type", "company");
            String dateFrom = requestParams.get("date_from");
            String dateTo = requestParams.get("date_to");

            String action = requestParams.get("action");
            if ("pending_count".equalsIgnoreCase(action)) {
                // 在统计侧栏/页签数字时，不按特定的租户类型过滤 counts，从而返回全部的 pending_count 总数
                Map<String, Object> stats = autoRenewService.getAutoRenewCounts(null, 30);
                Map<String, Object> counts = (Map<String, Object>) stats.get("counts");
                Integer pendingCount = (Integer) counts.get("pending");
                
                Map<String, Object> data = new java.util.HashMap<>();
                data.put("pending_count", pendingCount);
                body.put("success", true);
                body.put("message", "Pending count retrieved successfully");
                body.put("data", data);
                return ResponseEntity.ok(body);
            }

            // 获取特定页签的数据列表 (传递 entityType 以过滤 COMPANY / GROUP)
            Map<String, Object> data = autoRenewService.getAutoRenewList(status, entityType, dateFrom, dateTo);

            body.put("success", true);
            body.put("message", "Auto renew data retrieved successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            body.put("success", false);
            body.put("message", "Failed to retrieve auto renew data: " + e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(@RequestBody Map<String, Object> requestParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Object idObj = requestParams.get("request_id");
            if (idObj == null) {
                idObj = requestParams.get("requestId");
            }
            if (idObj == null) {
                throw new IllegalArgumentException("request_id is required");
            }
            Integer requestId = Integer.valueOf(idObj.toString());
            // 调用 Service 层执行拒绝逻辑
            autoRenewService.rejectRequest(requestId);
            body.put("success", true);
            body.put("message", "Auto renew request rejected successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("success", false);
            body.put("message", "Failed to reject auto renew request: " + e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody Map<String, Object> requestParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Object idObj = requestParams.get("request_id");
            if (idObj == null) {
                idObj = requestParams.get("requestId");
            }
            if (idObj == null) {
                throw new IllegalArgumentException("request_id is required");
            }
            Integer requestId = Integer.valueOf(idObj.toString());

            Object periodObj = requestParams.get("period");
            if (periodObj == null || String.valueOf(periodObj).trim().isEmpty()) {
                throw new IllegalArgumentException("period is required");
            }
            String period = String.valueOf(periodObj).trim();

            Map<String, Object> data = autoRenewService.approveRequest(requestId, period);
            body.put("success", true);
            body.put("message", "Auto renew request approved successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("success", false);
            body.put("message", e.getMessage() != null ? e.getMessage() : "Failed to approve auto renew request");
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
