package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.eazycount.dto.DomainDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/domain")
public class DomainController {

    @Autowired
    private DomainService domainService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "ownerId", required = false) Integer ownerId) {
        try {
            final List<OwnerTenantDTO> data = domainService.findAllTenantsByOwner(ownerId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Owner retrieved successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody DomainDTO domainDTO) {
        try {
            final DomainDTO data = domainService.createDomain(domainDTO);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Owner created successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
