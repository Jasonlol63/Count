package com.eazycount.controller;

import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.UserLink;
import com.eazycount.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
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
                    "message", "User retrieved successfully",
                    "data", data));
        } catch (Exception e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody UserListDTO userListDTO) {
        try {
            final UserListDTO data = userService.createUser(userListDTO);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User created successfully",
                    "data", data));
        } catch (Exception e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody UserListDTO userListDTO) {
        try {
            final UserListDTO data = userService.updateUser(userListDTO);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User updated successfully",
                    "data", data));
        } catch (Exception e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/updateStatus")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody UserListDTO userListDTO) {
        try {
            final UserListDTO data = userService.updateStatusByUserId(userListDTO.getId(),
                    userListDTO.getScopeTenantId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "User Status updated successfully",
                    "data", data));
        } catch (Exception e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody UserListDTO userListDTO) {
        try {
            userService.deleteUserByIdAndStatus(userListDTO.getId(), userListDTO.getScopeTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "User deleted successfully");
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

    @PostMapping("/link")
    public ResponseEntity<Map<String, Object>> link(@RequestBody UserLink userLink){
       try{
           userService.insertAccountLink(userLink);
           final Map<String, Object> body = new LinkedHashMap<>();
           body.put("success", true);
           body.put("message", "Account linked successfully");
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

    @DeleteMapping("/link/{id}")
    public ResponseEntity<Map<String, Object>> deleteAccountLink(@PathVariable long id) {
        try {
            userService.deleteAccountLinkById(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account link deleted successfully",
                    "data", null));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }

    @DeleteMapping("/link/account/{accountId}")
    public ResponseEntity<Map<String, Object>> deleteAccountLinkByAccountId(
            @PathVariable int accountId,
            @RequestParam("tenant_id") int tenantId) {
        try {
            userService.deleteAccountLinkByAccountId(accountId, tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account links deleted successfully",
                    "data", null));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }

    @DeleteMapping("/link/pair")
    public ResponseEntity<Map<String, Object>> deleteAccountLinkByPair(
            @RequestParam("account_id_1") int accountId1,
            @RequestParam("account_id_2") int accountId2,
            @RequestParam("tenant_id") int tenantId) {
        try {
            userService.deleteAccountLinkByPair(accountId1, accountId2, tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account link deleted successfully",
                    "data", null));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }

    @GetMapping("/link/list")
    public ResponseEntity<Map<String, Object>> getLinkedAccounts(
            @RequestParam("account_id") int accountId,
            @RequestParam("tenant_id") int tenantId) {
        try {
            Map<String, Object> data = userService.getLinkedAccounts(accountId, tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Linked accounts retrieved successfully",
                    "data", data));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }

    @GetMapping("/link/all")
    public ResponseEntity<Map<String, Object>> getAllLinkedAccounts(
            @RequestParam("account_id") int accountId,
            @RequestParam("tenant_id") int tenantId) {
        try {
            List<UserListDTO> data = userService.getAllLinkedAccounts(accountId, tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "All linked accounts retrieved successfully",
                    "data", data));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }

    @PutMapping("/link")
    public ResponseEntity<Map<String, Object>> updateAccountLink(@RequestBody UserLink userLink) {
        try {
            userService.updateAccountLink(userLink);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account link updated successfully",
                    "data", null));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "data", null));
        }
    }
}
