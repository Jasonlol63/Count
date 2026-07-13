package com.eazycount.service;

import com.eazycount.dto.AdminDTO;

import java.util.List;

public interface AdminService {

    List<AdminDTO> findAdminsByTenantId(Integer tenantId);

    AdminDTO getAdminDetailByUserId(Integer userId, Integer scopeTenantId);

    AdminDTO createAdmin(AdminDTO dto);

    AdminDTO updateAdmin(AdminDTO dto);

    AdminDTO updateOwnerProfile(AdminDTO dto);

    AdminDTO updateStatusById(Integer userId, Integer scopeTenantId);

    void deleteAdminByIdAndStatus(Integer userId, Integer scopeTenantId);
}
