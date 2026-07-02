package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Tenant;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "ownerId", required = false) Integer ownerId) {
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

    @PutMapping("/update-setting")
    public ResponseEntity<Map<String, Object>> updateSetting(@RequestBody Tenant tenant) {
        try{
            domainService.updateTenantDetailsSetting(tenant);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Domain setting updated successfully");
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


    @PutMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody DomainDTO domainDTO) {
        try {
            DomainDTO data = domainService.updateDomain(domainDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Domain updated successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/list-fee")
    public ResponseEntity<Map<String, Object>> listFee() {
        try{
            List<DomainFee> domainFees = domainService.findAllDomainFee();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Domain Fee retrieved successfully",
                    "data", domainFees));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add-fee")
    public ResponseEntity<Map<String, Object>> Insertfee(@RequestBody DomainFee domainFee) {
        try{
            DomainFee fee = domainService.updateDomainFee(domainFee);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Domain Fee updated successfully",
                    "data", fee));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
