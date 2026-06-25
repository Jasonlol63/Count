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

import java.util.List;

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
