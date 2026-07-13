package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AdminDTO;
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
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "tenant_id") Integer tenantId) {
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

    @PostMapping("/get")
    public ResponseEntity<Map<String, Object>> get(@RequestParam("user_id") Integer userId, @RequestParam("scope_tenant_id") Integer scopeTenantId) {
        try {
            AdminDTO data = adminService.getAdminDetailByUserId(userId, scopeTenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin detail retrieved successfully",
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

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody AdminDTO dto) {
        try{
            AdminDTO data = adminService.createAdmin(dto);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin created successfully",
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
    public ResponseEntity<Map<String, Object>> update(@RequestBody AdminDTO dto) {
        try {
            AdminDTO data = adminService.updateAdmin(dto);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin updated successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update-owner-profile")
    public ResponseEntity<Map<String, Object>> updateOwnerProfile(@RequestBody AdminDTO dto) {
        try {
            AdminDTO data = adminService.updateOwnerProfile(dto);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Owner updated successfully",
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
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody AdminDTO dto){
        try{
            AdminDTO data = adminService.updateStatusById(dto.getId(), dto.getScopeTenantId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin Status updated successfully",
                    "data", data));
        }catch (BusinessException e){
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteAdmin(@RequestBody AdminDTO dto){
        try{
            adminService.deleteAdminByIdAndStatus(dto.getId(), dto.getScopeTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Admin deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
