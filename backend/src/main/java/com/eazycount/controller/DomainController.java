package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.*;
import com.eazycount.service.DomainService;
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
@RequestMapping("/api/domain")
public class DomainController {

    @Autowired
    private DomainService domainService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        final List<DomainListItemDto> domains = domainService.listDomainsForApi();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data", Map.of("domains", domains)
        ));
    }

    @PostMapping("/validate-code")
    public ResponseEntity<Map<String, Object>> validateCode(@RequestBody ValidateDomainCodeRequest request) {
        final String code = request != null && request.getCode() != null
                ? request.getCode().trim().toUpperCase()
                : "";

        try {
            domainService.validateDomainCode(
                    request != null ? request.getCode() : null,
                    request != null ? request.getExcludeOwnerId() : null
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "OK",
                    "data", Map.of("available", true, "code", code)
            ));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", Map.of("available", false, "code", code));
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody CreateDomainRequest request) {
        try {
            final DomainListItemDto data = domainService.createDomain(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Owner created successfully",
                    "data", data
            ));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateDomainRequest request) {
        try {
            final DomainListItemDto data = domainService.updateDomain(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Owner updated successfully",
                    "data", data
            ));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody DeleteDomainRequest request) {
        try {
            domainService.deleteDomain(request != null ? request.getId() : null);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Owner and all related data deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
