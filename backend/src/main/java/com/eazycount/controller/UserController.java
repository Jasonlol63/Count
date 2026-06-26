package com.eazycount.controller;

import com.eazycount.dto.AdminRequest;
import com.eazycount.dto.UserListDTO;
import com.eazycount.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "tenant_id") Integer tenantId) {
        try {
            final List<UserListDTO> data = userService.findUserByTenantId(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Admin retrieved successfully",
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
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
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody UserRequest userRequest) {
        try {
            final UserListDTO data = userService.createUser(userRequest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User created successfully",
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody UserRequest userRequest) {
        try {
            final UserListDTO data = userService.updateUser(userRequest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User updated successfully",
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/updateStatus")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody UserRequest userRequest){
        try {
            final UserListDTO data = userService.updateStatusByUserId(userRequest.getId(), userRequest.getTenantId())
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User Status updated successfully",
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody UserRequest userRequest) {
        try {
            userService.deleteUserByIdAndStatus(userRequest.getId(), userRequest.getScopeTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "User deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
