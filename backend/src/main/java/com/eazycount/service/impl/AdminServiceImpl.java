package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AdminDao;
import com.eazycount.dto.AdminDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminDao adminDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public AdminServiceImpl(AdminDao adminDao) {
        this.adminDao = adminDao;
    }

    @Override
    public List<AdminDTO> findAdminsByTenantId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        return adminDao.findAdminsByTenantId(tenantId);
    }

    @Override
    public void insertAdmin(Admin admin) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (admin == null) {
            throw new BusinessException("Invalid Admin");
        }

        final String normalizedLoginId = admin.getLoginId().trim().toUpperCase();
        final String normalizedName = admin.getName().trim();
        final String normalizedEmail = admin.getEmail().trim().toLowerCase();

        admin.setLoginId(normalizedLoginId);
        admin.setName(normalizedName);
        admin.setEmail(normalizedEmail);

        if (adminDao.findDuplicateLoginId(admin.getLoginId()) != null) {
            throw new BusinessException("Duplicate Login Id!");
        }
        if (adminDao.findDuplicateEmail(admin.getEmail()) != null) {
            throw new BusinessException("Duplicate Email!");
        }

        admin.setPassword(passwordEncoder.encode(admin.getPassword()));
        if(admin.getSecondaryPassword() == null || admin.getSecondaryPassword().isBlank()) {
            admin.setSecondaryPassword(null);
        }else{
            admin.setSecondaryPassword(passwordEncoder.encode(admin.getSecondaryPassword()));
        }

        admin.setStatus(Admin.UserStatus.ACTIVE);
        admin.setReadOnly(false);
        admin.setCreatedBy(session.login_id);

        try{
            adminDao.insertAdmin(admin);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Failed!");
        }
    }

    @Override
    public void insertAdminTenantAccess(AdminTenantAccess access) {
        if (access == null) {
            throw new BusinessException("Invalid Admin Tenant Access");
        }

        try{
            access.setAccountAclMode(access.getAccountAclMode());
            access.setProcessAclMode(access.getProcessAclMode());
            adminDao.insertAdminTenantAccess(access);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Access Failed!");
        }
    }

    @Override
    public void insertAdminTenantAccountAccessBatch(List<AdminTenantAccountAccess> list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        for (AdminTenantAccountAccess access : list) {
            access.setAccountId(access.getAccountId());
            access.setUserTenantAccessId(access.getUserTenantAccessId());

        }

        try{
            adminDao.insertAdminTenantAccountAccessBatch(list);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Account Access Failed!");
        }
    }

    @Override
    public void insertAdminTenantProcessAccessBatch(List<AdminTenantProcessAccess> list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        for(AdminTenantProcessAccess access : list) {
            access.setProcessId(access.getProcessId());
            access.setUserTenantAccessId(access.getUserTenantAccessId());
        }

        try{
            adminDao.insertAdminTenantProcessAccessBatch(list);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Process Access Failed!");
        }
    }
}
