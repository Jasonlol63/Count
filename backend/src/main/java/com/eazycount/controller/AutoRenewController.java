package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AutoRenewApprovalRequest;
import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.AutoRenewListResponseDTO;
import com.eazycount.dto.AutoRenewRequestDTO;
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
    public ResponseEntity<Map<String, Object>> list(@RequestBody AutoRenewRequestDTO request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String status = request.getStatus() != null ? request.getStatus() : "pending";
            String entityType = request.getEntityType() != null ? request.getEntityType() : "company";
            String dateFrom = request.getDateFrom();
            String dateTo = request.getDateTo();

            if ("pending_count".equalsIgnoreCase(request.getAction())) {
                // 在统计侧栏/页签数字时，不按特定的租户类型过滤 counts，从而返回全部的 pending_count 总数
                AutoRenewDTO stats = autoRenewService.getAutoRenewCounts(null, 30, null, null);
                Integer pendingCount = stats.getCounts() != null ? stats.getCounts().getPending() : null;

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("pending_count", pendingCount);
                body.put("success", true);
                body.put("message", "Pending count retrieved successfully");
                body.put("data", data);
                return ResponseEntity.ok(body);
            }

            // 获取特定页签的数据列表 (传递 entityType 以过滤 COMPANY / GROUP)
            AutoRenewListResponseDTO data = autoRenewService.getAutoRenewList(status, entityType, dateFrom, dateTo);

            body.put("success", true);
            body.put("message", "Auto renew data retrieved successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);

        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(@RequestBody AutoRenewApprovalRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (request.getRequestId() == null) {
                throw new BusinessException("request_id is required");
            }
            // 调用 Service 层执行拒绝逻辑
            autoRenewService.rejectRequest(request.getRequestId());
            body.put("success", true);
            body.put("message", "Auto renew request rejected successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody AutoRenewApprovalRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (request.getRequestId() == null) {
                throw new BusinessException("request_id is required");
            }
            String period = request.getPeriod() != null ? request.getPeriod().trim() : "";
            if (period.isEmpty()) {
                throw new BusinessException("period is required");
            }

            AutoRenewDTO data = autoRenewService.approveRequest(request.getRequestId(), period);
            body.put("success", true);
            body.put("message", "Auto renew request approved successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody AutoRenewRequestDTO request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (request.getRequestId() == null) {
                throw new BusinessException("request_id is required");
            }
            autoRenewService.deleteRequest(request.getRequestId());
            body.put("success", true);
            body.put("message", "Auto renew request reverted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(BusinessException e) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
