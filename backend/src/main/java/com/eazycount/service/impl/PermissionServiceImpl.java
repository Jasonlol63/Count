package com.eazycount.service.impl;

import com.eazycount.dao.PermissionDao;
import com.eazycount.entity.Admin;
import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Permission;
import com.eazycount.entity.AdminRole;
import com.eazycount.entity.Tenant;
import com.eazycount.service.PermissionService;
import com.eazycount.util.TenantDtoHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PermissionServiceImpl implements PermissionService {

    private static final String GAME_CODE = "GAME";
    private static final String BANK_CODE = "BANK";
    private static final String OWNER_ROLE_CODE = "OWNER";
    private static final List<String> C168_EXTRA_PERMISSION_CODES = List.of("DOMAIN", "ANNOUNCEMENTS");

    @Autowired
    private PermissionDao permissionDao;

    @Override
    public List<String> resolveAdminModuleKeys(Admin admin, Tenant tenant, List<FeatureModule> featureModules) {
        if (admin == null || admin.getRoleId() == null) {
            return List.of();
        }
        return resolveModuleKeysForRoleId(admin.getRoleId(), tenant, featureModules);
    }

    @Override
    public List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant, List<FeatureModule> featureModules) {
        if (owner == null) {
            return List.of();
        }
        AdminRole ownerRole = permissionDao.findStaffRoleByCode(OWNER_ROLE_CODE);
        if (ownerRole == null || ownerRole.getId() == null) {
            return List.of();
        }
        return resolveModuleKeysForRoleId(ownerRole.getId(), tenant, featureModules);
    }

    @Override
    public boolean hasGameModule(List<FeatureModule> featureModules) {
        return TenantDtoHelper.hasFeatureCode(featureModules, GAME_CODE);
    }

    @Override
    public boolean hasBankModule(List<FeatureModule> featureModules) {
        return TenantDtoHelper.hasFeatureCode(featureModules, BANK_CODE);
    }

    @Override
    public boolean isC168Account(Tenant tenant) {
        if (tenant == null || tenant.getCode() == null) {
            return false;
        }
        return "C168".equalsIgnoreCase(tenant.getCode().trim());
    }

    private List<String> resolveModuleKeysForRoleId(
            int roleId,
            Tenant tenant,
            List<FeatureModule> featureModules
    ) {
        Map<String, Permission> effective = new LinkedHashMap<>();

        for (Permission permission : permissionDao.findActivePermissionsByRoleId(roleId)) {
            putPermission(effective, permission);
        }

        if (isC168Account(tenant)) {
            for (Permission permission : permissionDao.findActivePermissionsByCodes(C168_EXTRA_PERMISSION_CODES)) {
                putPermission(effective, permission);
            }
        }

        Set<Integer> tenantFeatureIds = tenantFeatureModuleIds(featureModules);

        return effective.values().stream()
                .filter(permission -> passesFeatureGate(permission, tenantFeatureIds))
                .sorted(Comparator
                        .comparing(Permission::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Permission::getId, Comparator.nullsLast(Integer::compareTo)))
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .map(this::normalizeCode)
                .distinct()
                .toList();
    }

    private static void putPermission(Map<String, Permission> target, Permission permission) {
        if (permission == null || permission.getCode() == null || permission.getCode().isBlank()) {
            return;
        }
        target.put(permission.getCode().trim().toUpperCase(), permission);
    }

    private boolean passesFeatureGate(Permission permission, Set<Integer> tenantFeatureIds) {
        Integer requiredFeatureId = permission.getRequiresFeatureId();
        if (requiredFeatureId == null) {
            return true;
        }
        return tenantFeatureIds.contains(requiredFeatureId);
    }

    private Set<Integer> tenantFeatureModuleIds(List<FeatureModule> featureModules) {
        if (featureModules == null || featureModules.isEmpty()) {
            return Set.of();
        }
        return featureModules.stream()
                .map(FeatureModule::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }
}
