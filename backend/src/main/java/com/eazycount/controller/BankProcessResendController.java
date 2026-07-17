package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.service.BankProcessResendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bank-process/resend")
public class BankProcessResendController {

    @Autowired
    private BankProcessResendService bankProcessResendService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> resend(@RequestBody AccountingDueDTO request) {
        try {
            if (request == null || request.getTenantId() == null) {
                throw new BusinessException("Invalid Tenant Id!");
            }
            final AccountingDueDTO data = bankProcessResendService.resend(request);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Resend successful");
            body.put("data", data);
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
