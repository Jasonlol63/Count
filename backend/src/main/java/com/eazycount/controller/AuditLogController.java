package com.eazycount.controller;

import com.eazycount.dto.AuditLogDTO;
import com.eazycount.entity.AuditLog;
import com.eazycount.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/it")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @GetMapping("/audit-log")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuditLogDTO query = toQuery(dateFrom, dateTo, tenantCode, module, action, keyword);
        AuditLogDTO result = auditLogService.search(query, page, size);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Log List Successfully");
        body.put("data", result.getItems());
        body.put("total", result.getTotal());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/audit-log/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String keyword
    ) {
        AuditLogDTO query = toQuery(dateFrom, dateTo, tenantCode, module, action, keyword);
        AuditLogDTO result = auditLogService.summary(query);
        return ResponseEntity.ok(Map.of("success", true, "message", "OK", "data", result));
    }

    private static AuditLogDTO toQuery(
            String dateFrom, String dateTo, String tenantCode, String module, String action, String keyword
    ) {
        AuditLogDTO query = new AuditLogDTO();
        query.setDateFrom(parseDate(dateFrom));
        query.setDateTo(parseDate(dateTo));
        query.setTenantCode(blankToNull(tenantCode));
        query.setModule(blankToNull(module));
        query.setAction(parseAction(action));
        query.setKeyword(blankToNull(keyword));
        return query;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim());
    }

    private static AuditLog.Action parseAction(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return AuditLog.Action.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
