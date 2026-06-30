package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AdminListDTO;
import com.eazycount.dto.AdminRequest;
import com.eazycount.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(value = "tenant_id") Integer tenantId) {
        try {
            final List<AdminListDTO> data = adminService.findAdminsByTenantId(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin retrieved successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/get")
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam("user_id") Integer userId,
            @RequestParam("scope_tenant_id") Integer scopeTenantId) {
        try {
            final AdminRequest data = adminService.getAdminDetail(userId, scopeTenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User found",
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
    public ResponseEntity<Map<String, Object>> add(@RequestBody AdminRequest adminRequest) {
        try {
            final AdminListDTO data = adminService.createAdmin(adminRequest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User created successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody AdminRequest adminRequest) {
        try {
            final AdminListDTO data = adminService.updateAdmin(adminRequest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User updated successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/updateStatus")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody AdminRequest admin) {
        try {
            final AdminListDTO data = adminService.updateStatusByAdminId(admin.getId(), admin.getScopeTenantId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin Status updated successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody AdminRequest adminRequest) {
        try {
            adminService.deleteAdminByIdAndStatus(adminRequest.getId(), adminRequest.getScopeTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Admin deleted successfully");
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
