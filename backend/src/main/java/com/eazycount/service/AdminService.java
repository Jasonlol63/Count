package com.eazycount.service;

import com.eazycount.dto.AdminListDTO;

import java.util.List;

public interface AdminService {

    List<AdminListDTO> findAdminsByTenantId(Integer tenantId);

}
