package com.eazycount.service;

import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;

import java.util.List;

/**
 * Resolves sidebar module permissions and tenant feature flags for session/bootstrap.
 */
public interface PermissionService {

    /** Effective sidebar module keys: C168 full list, else tenant template ∩ role ∩ optional user JSON. */
    List<String> resolveAdminModuleKeys(Admin admin, Tenant tenant);
    /**
     * Owner session permissions. C168 company returns full module list; otherwise empty list
     * (frontend treats empty as unrestricted).
     */
    List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant);

    boolean hasGamblingModule(Tenant tenant);

    boolean hasBankModule(Tenant tenant);

    boolean isC168Account(Tenant tenant);
}
