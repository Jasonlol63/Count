package com.eazycount.controller;

import com.eazycount.security.SecurityUtils;
import com.eazycount.service.SystemMaintenanceModeService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/it")
public class SystemMaintenanceModeController {

    @Autowired
    private SystemMaintenanceModeService systemMaintenanceModeService;

    @GetMapping("/maintenance-mode")
    public ResponseEntity<Map<String, Object>> status() {
        AccessControlUtils.requireItOperator(SecurityUtils.currentUser());
        boolean enabled = systemMaintenanceModeService.isEnabled();
        return ResponseEntity.ok(Map.of("success", true, "message", "OK", "data", Map.of("enabled", enabled)));
    }

    @PostMapping("/maintenance-mode")
    public ResponseEntity<Map<String, Object>> toggle(@RequestParam boolean enabled) {
        systemMaintenanceModeService.setEnabled(enabled);
        String message = enabled ? "Maintenance mode enabled" : "Maintenance mode disabled";
        return ResponseEntity.ok(Map.of("success", true, "message", message, "data", Map.of("enabled", enabled)));
    }
}
