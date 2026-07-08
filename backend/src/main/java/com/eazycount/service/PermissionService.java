package com.eazycount.service;

import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;

import java.util.List;

/**
 * Resolves sidebar module permissions and tenant feature flags for session/bootstrap.
 */
public interface PermissionService {

    /** Staff/admin sidebar keys (uppercase) from {@code user.role_id} + tenant rules. */
    List<String> resolveAdminModuleKeys(Admin admin, Tenant tenant);

    /** Owner sidebar keys (uppercase) from OWNER role in {@code user_role_permission} + tenant rules. */
    List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant);

    boolean hasGameModule(Tenant tenant);

    boolean hasBankModule(Tenant tenant);

    boolean isC168Account(Tenant tenant);
}
