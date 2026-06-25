package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AdminDao;
import com.eazycount.dto.AdminListDTO;
import com.eazycount.dto.AdminRequest;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AdminServiceImpl implements AdminService {
    @Autowired
    private AdminDao adminDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<AdminListDTO> findAdminsByTenantId(Integer tenantId) {
        if(tenantId == null){
            throw new BusinessException("Invalid Tenant Id!");
        }
        return adminDao.findAdminsByTenantId(tenantId);
    }

    @Transactional
    @Override
    public AdminListDTO createAdmin(AdminRequest adminRequest) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (adminRequest == null) {
            throw new BusinessException("Invalid request");
        }
        if (adminRequest.getLoginId() == null || adminRequest.getLoginId().isBlank()) {
            throw new BusinessException("Login ID is required");
        }
        if (adminRequest.getName() == null || adminRequest.getName().isBlank()) {
            throw new BusinessException("Name is required");
        }
        if (adminRequest.getEmail() == null || adminRequest.getEmail().isBlank()) {
            throw new BusinessException("Email is required");
        }
        if (adminRequest.getRole() == null || adminRequest.getRole().isBlank()) {
            throw new BusinessException("Role is required");
        }
        if (adminRequest.getPassword() == null || adminRequest.getPassword().isBlank()) {
            throw new BusinessException("Password is required");
        }
        if (adminRequest.getTenantIds() == null || adminRequest.getTenantIds().isEmpty()) {
            throw new BusinessException("At least one tenant is required");
        }

        Admin admin = new Admin();
        admin.setLoginId(adminRequest.getLoginId().trim().toUpperCase());
        admin.setName(adminRequest.getName() != null ? adminRequest.getName().trim() : "");
        admin.setEmail(adminRequest.getEmail() != null ? adminRequest.getEmail().trim() : "");
        admin.setPassword(passwordEncoder.encode(adminRequest.getPassword()));
        if (adminRequest.getSecondaryPassword() != null && !adminRequest.getSecondaryPassword().isBlank()) {
            admin.setSecondaryPassword(passwordEncoder.encode(adminRequest.getSecondaryPassword().trim()));
        }
        admin.setRole(Admin.UserRole.fromValue(adminRequest.getRole()));
        admin.setStatus(adminRequest.getStatus() != null
                ? Admin.UserStatus.fromValue(adminRequest.getStatus())
                : Admin.UserStatus.ACTIVE);
        admin.setPermissions(toJson(adminRequest.getPermissions()));
        admin.setCreatedBy(session.login_id);
        admin.setReadOnly(adminRequest.getReadOnly() != null ? adminRequest.getReadOnly() : Boolean.TRUE);

        adminDao.addAdmin(admin); // useGeneratedKeys 回填 admin.id

        String accountJson = toJson(adminRequest.getAccountPermissions());
        String processJson = toJson(adminRequest.getProcessPermissions());

        AdminTenantAccess firstAccess = null;
        for (Integer tenantId : adminRequest.getTenantIds()) {
            if (tenantId == null || tenantId <= 0) continue;

            AdminTenantAccess access = new AdminTenantAccess();
            access.setUserId(admin.getId());
            access.setTenantId(tenantId);
            access.setAccountPermissions(accountJson);
            access.setProcessPermissions(processJson);
            adminDao.insertAdminTenantAccess(access);

            if (firstAccess == null) {
                firstAccess = access;
            }
        }

        if (firstAccess == null) {
            throw new BusinessException("At least one valid tenant is required");
        }

        AdminListDTO result = new AdminListDTO();
        result.setAdmin(admin);
        result.setAdminTenantAccess(firstAccess);
        return result;
    }

    @Transactional
    @Override
    public AdminListDTO updateAdmin(AdminRequest adminRequest) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (adminRequest == null
                || adminRequest.getId() == null || adminRequest.getId() <= 0
                || adminRequest.getScopeTenantId() == null || adminRequest.getScopeTenantId() <= 0) {
            throw new BusinessException("Invalid request");
        }

        final int userId = adminRequest.getId();
        final int scopeTenantId = adminRequest.getScopeTenantId();

        AdminListDTO existing = adminDao.findAdminByUserIdAndTenantId(userId, scopeTenantId);
        if (existing == null || existing.getAdmin() == null || existing.getAdminTenantAccess() == null) {
            throw new BusinessException("User not found or access denied");
        }

        AdminTenantAccess existingAccess = existing.getAdminTenantAccess();
        if (adminRequest.getTenantAccessId() != null
                && !adminRequest.getTenantAccessId().equals(existingAccess.getId())) {
            throw new BusinessException("Tenant access mismatch");
        }

        Admin patch = new Admin();
        patch.setId(userId);

        if (adminRequest.getName() != null && !adminRequest.getName().isBlank()) {
            patch.setName(adminRequest.getName().trim());
        }
        if (adminRequest.getEmail() != null && !adminRequest.getEmail().isBlank()) {
            final String email = adminRequest.getEmail().trim();
            if (adminDao.countEmailExceptUser(email, userId) > 0) {
                throw new BusinessException("Duplicate email");
            }
            patch.setEmail(email);
        }
        if (adminRequest.getRole() != null && !adminRequest.getRole().isBlank()) {
            patch.setRole(Admin.UserRole.fromValue(adminRequest.getRole()));
        }
        if (adminRequest.getStatus() != null && !adminRequest.getStatus().isBlank()) {
            patch.setStatus(Admin.UserStatus.fromValue(adminRequest.getStatus()));
        }
        if (adminRequest.getPermissions() != null) {
            patch.setPermissions(toJson(adminRequest.getPermissions()));
        }
        if (adminRequest.getReadOnly() != null) {
            patch.setReadOnly(adminRequest.getReadOnly());
        }
        if (adminRequest.getPassword() != null && !adminRequest.getPassword().isBlank()) {
            patch.setPassword(passwordEncoder.encode(adminRequest.getPassword().trim()));
        }
        if (adminRequest.getSecondaryPassword() != null && !adminRequest.getSecondaryPassword().isBlank()) {
            patch.setSecondaryPassword(passwordEncoder.encode(adminRequest.getSecondaryPassword().trim()));
        }

        if (hasUserPatch(patch)) {
            adminDao.updateAdmin(patch);
        }

        AdminTenantAccess accessPatch = new AdminTenantAccess();
        accessPatch.setId(existingAccess.getId());
        boolean tenantAccessUpdated = false;
        if (adminRequest.getAccountPermissions() != null) {
            accessPatch.setAccountPermissions(toJson(adminRequest.getAccountPermissions()));
            tenantAccessUpdated = true;
        }
        if (adminRequest.getProcessPermissions() != null) {
            accessPatch.setProcessPermissions(toJson(adminRequest.getProcessPermissions()));
            tenantAccessUpdated = true;
        }
        if (tenantAccessUpdated) {
            adminDao.updateAdminTenantAccess(accessPatch);
        }

        String accountPermissions = existingAccess.getAccountPermissions();
        String processPermissions = existingAccess.getProcessPermissions();
        if (adminRequest.getAccountPermissions() != null) {
            accountPermissions = accessPatch.getAccountPermissions();
        }
        if (adminRequest.getProcessPermissions() != null) {
            processPermissions = accessPatch.getProcessPermissions();
        }

        if (adminRequest.getTenantIds() != null && !adminRequest.getTenantIds().isEmpty()) {
            syncTenantAccess(userId, adminRequest.getTenantIds(), accountPermissions, processPermissions);
        }

        AdminListDTO result = adminDao.findAdminByUserIdAndTenantId(userId, scopeTenantId);
        if (result == null) {
            throw new BusinessException(
                    "User updated successfully, but no longer belongs to the current tenant"
            );
        }
        return result;
    }

    private boolean hasUserPatch(Admin patch) {
        return patch.getName() != null
                || patch.getEmail() != null
                || patch.getRole() != null
                || patch.getStatus() != null
                || patch.getPermissions() != null
                || patch.getReadOnly() != null
                || patch.getPassword() != null
                || patch.getSecondaryPassword() != null;
    }

    private void syncTenantAccess(
            int userId,
            List<Integer> tenantIds,
            String accountPermissions,
            String processPermissions
    ) {
        List<Integer> desired = new ArrayList<>();
        for (Integer tenantId : tenantIds) {
            if (tenantId != null && tenantId > 0 && !desired.contains(tenantId)) {
                desired.add(tenantId);
            }
        }
        if (desired.isEmpty()) {
            throw new BusinessException("At least one tenant is required");
        }

        Set<Integer> existing = new HashSet<>(adminDao.findTenantIdsByUserId(userId));
        Set<Integer> target = new HashSet<>(desired);

        for (Integer tenantId : target) {
            if (!existing.contains(tenantId)) {
                AdminTenantAccess access = new AdminTenantAccess();
                access.setUserId(userId);
                access.setTenantId(tenantId);
                access.setAccountPermissions(accountPermissions);
                access.setProcessPermissions(processPermissions);
                adminDao.insertAdminTenantAccess(access);
            }
        }

        for (Integer tenantId : existing) {
            if (!target.contains(tenantId)) {
                adminDao.deleteAdminTenantAccessByUserIdAndTenantId(userId, tenantId);
            }
        }
    }



    private String toJson (Object value){
        if (value == null) return null;
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("Invalid JSON field");
        }
    }

}
