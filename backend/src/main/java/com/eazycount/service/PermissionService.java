package com.eazycount.service;

import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;

import java.util.List;

/**
 * Resolves sidebar module permissions and tenant feature flags for session/bootstrap.
 */
public interface PermissionService {

    List<String> resolveAdminModuleKeys(Admin admin, Tenant tenant);

    List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant);

    boolean hasGameModule(Tenant tenant);

    boolean hasBankModule(Tenant tenant);

    boolean isC168Account(Tenant tenant);

}
