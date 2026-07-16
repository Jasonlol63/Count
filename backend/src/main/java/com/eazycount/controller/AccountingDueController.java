package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.AccountingDueInboxRequest;
import com.eazycount.service.AccountingDueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bank-process/accounting-due")
public class AccountingDueController {

    @Autowired
    private AccountingDueService accountingDueService;

    @PostMapping("/inbox")
    public ResponseEntity<Map<String, Object>> inbox(@RequestBody AccountingDueInboxRequest request) {
        try {
            if (request == null || request.getTenantId() == null) {
                throw new BusinessException("Invalid Tenant Id!");
            }
            boolean restoreSkipped = Boolean.TRUE.equals(request.getRestoreSkipped());
            final List<AccountingDueDTO> due = accountingDueService.resolveInbox(
                    request.getTenantId(), request.getAsOf(), restoreSkipped);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Accounting due retrieved successfully");
            body.put("data", due);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/skip")
    public ResponseEntity<Map<String, Object>> skip(@RequestBody List<AccountingDueDTO> items) {
        try {
            accountingDueService.skipPeriods(items);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Accounting due skipped successfully");
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
