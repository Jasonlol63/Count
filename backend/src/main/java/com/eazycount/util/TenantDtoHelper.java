package com.eazycount.util;

import com.eazycount.dto.TenantDTO;
import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TenantDtoHelper {

    private TenantDtoHelper() {
    }

    public static List<Tenant> distinctTenants(List<TenantDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Integer, Tenant> tenants = new LinkedHashMap<>();
        for (TenantDTO row : rows) {
            if (row == null || row.getTenant() == null || row.getTenant().getId() == null) {
                continue;
            }
            tenants.putIfAbsent(row.getTenant().getId(), row.getTenant());
        }
        return List.copyOf(tenants.values());
    }

    public static List<FeatureModule> featureModulesForTenant(List<TenantDTO> rows, int tenantId) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<FeatureModule> modules = new ArrayList<>();
        for (TenantDTO row : rows) {
            if (row == null || row.getTenant() == null || row.getTenant().getId() == null) {
                continue;
            }
            if (row.getTenant().getId() != tenantId) {
                continue;
            }
            FeatureModule module = row.getFeatureModule();
            if (module != null && module.getId() != null) {
                modules.add(module);
            }
        }
        return List.copyOf(modules);
    }

    public static boolean hasFeatureCode(List<FeatureModule> modules, String code) {
        if (modules == null || modules.isEmpty() || code == null || code.isBlank()) {
            return false;
        }
        String normalized = code.trim().toUpperCase();
        return modules.stream()
                .filter(Objects::nonNull)
                .map(FeatureModule::getCode)
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase())
                .anyMatch(normalized::equals);
    }
}
