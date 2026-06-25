package com.eazycount.service;

import com.eazycount.dto.AdminListDTO;
import com.eazycount.dto.AdminRequest;

import java.util.List;

public interface AdminService {

    List<AdminListDTO> findAdminsByTenantId(Integer tenantId);

    AdminListDTO createAdmin(AdminRequest adminRequest);

    AdminListDTO updateAdmin(AdminRequest adminRequest);
}
