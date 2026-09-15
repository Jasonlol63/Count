package com.eazycount.controller;

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
        final List<AdminDTO> data = adminService.findAdminsByTenantId(tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin retrieved successfully",
                "data", data
        ));
    }

    @PostMapping("/get")
    public ResponseEntity<Map<String, Object>> get(@RequestParam("user_id") Integer userId, @RequestParam("scope_tenant_id") Integer scopeTenantId) {
        AdminDTO data = adminService.getAdminDetailByUserId(userId, scopeTenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin detail retrieved successfully",
                "data", data
        ));
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody AdminDTO dto) {
        AdminDTO data = adminService.createAdmin(dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin created successfully",
                "data", data));
    }


    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody AdminDTO dto) {
        AdminDTO data = adminService.updateAdmin(dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin updated successfully",
                "data", data));
    }

    @PostMapping("/update-owner-profile")
    public ResponseEntity<Map<String, Object>> updateOwnerProfile(@RequestBody AdminDTO dto) {
        AdminDTO data = adminService.updateOwnerProfile(dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Owner updated successfully",
                "data", data));
    }

    @PostMapping("/updateStatus")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody AdminDTO dto){
        AdminDTO data = adminService.updateStatusById(dto.getId(), dto.getScopeTenantId());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin Status updated successfully",
                "data", data));
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteAdmin(@RequestBody AdminDTO dto){
        adminService.deleteAdminByIdAndStatus(dto.getId(), dto.getScopeTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Admin deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
