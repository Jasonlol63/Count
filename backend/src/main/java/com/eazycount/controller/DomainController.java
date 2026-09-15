package com.eazycount.controller;

import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        final List<OwnerTenantDTO> data = domainService.findAllTenantsByOwner(ownerId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Owner retrieved successfully",
                "data", data));
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody DomainDTO domainDTO) {
        final DomainDTO data = domainService.createDomain(domainDTO);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Owner created successfully",
                "data", data));
    }

    @PutMapping("/update-setting")
    public ResponseEntity<Map<String, Object>> updateSetting(@RequestBody Tenant tenant) {
        domainService.updateTenantDetailsSetting(tenant);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Domain setting updated successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }


    @PutMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody DomainDTO domainDTO) {
        DomainDTO data = domainService.updateDomain(domainDTO);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Domain updated successfully");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Owner owner) {
        domainService.deleteOwnerDetails(owner);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Domain Deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/list-fee")
    public ResponseEntity<Map<String, Object>> listFee() {
        DomainFeeSettingsDTO settings = domainService.findDomainFeeSettings();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Domain Fee retrieved successfully",
                "data", List.of(settings)));
    }

    @PostMapping("/add-fee")
    public ResponseEntity<Map<String, Object>> Insertfee(@RequestBody DomainFeeSettingsDTO settings) {
        DomainFeeSettingsDTO fee = domainService.updateDomainFeeSettings(settings);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Domain Fee updated successfully",
                "data", fee));
    }
}
