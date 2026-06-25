package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AdminDao;
import com.eazycount.dto.AdminListDTO;
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

    @Override
    public List<AdminListDTO> findAdminsByTenantId(Integer tenantId) {
        if(tenantId == null){
            throw new BusinessException("Invalid Tenant Id!");
        }
        return adminDao.findAdminsByTenantId(tenantId);
    }
}
