package com.eazycount.service;

import com.eazycount.dto.AdminListDTO;
import com.eazycount.dto.AdminRequest;
import com.eazycount.entity.Admin;

import java.util.List;

public interface AdminService {

    List<AdminListDTO> findAdminsByTenantId(Integer tenantId);

    AdminRequest getAdminDetail(Integer userId, Integer scopeTenantId);

    AdminListDTO createAdmin(AdminRequest adminRequest);

    AdminListDTO updateAdmin(AdminRequest adminRequest);

    AdminListDTO updateStatusByAdminId(Integer userId, Integer scopeTenantId);

    void deleteAdminByIdAndStatus(Integer userId, Integer scopeTenantId);
}
