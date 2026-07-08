package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AdminDTO;
import com.eazycount.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/userlist")
public class AdminController {

    @Autowired
    private AdminService adminService;


    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "tenant_id") Integer tenantId
    ) {
        try {
            final List<AdminDTO> data = adminService.findAdminsByTenantId(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin retrieved successfully",
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
}
