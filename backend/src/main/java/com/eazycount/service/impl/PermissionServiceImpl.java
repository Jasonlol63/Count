package com.eazycount.service.impl;

import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Tenant.TenantType;
import com.eazycount.service.PermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;



@Service

public class PermissionServiceImpl implements PermissionService {

    /** Internal module keys (uppercase); order matches frontend {@code PERMISSION_KEYS}. */
    private static final List<String> LIST_FULL_PERMISSIONS = List.of(
            "HOME", "DOMAIN", "ANNOUNCEMENTS", "AUTORENEW", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS",
            "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE"
    );
    
    /** GROUP + GAME: fixed sidebar for all roles — no {@code PROCESS}, no role/JSON narrowing. */
    private static final List<String> GROUP_FULL_PERMISSIONS = List.of(
        "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE"
    );

    private static final Map<String, List<String>> ROLE_PERMISSION_MAP = Map.ofEntries(
        Map.entry("PARTNERSHIP", List.of( "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
        Map.entry("ADMIN", List.of( "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
        Map.entry("MANAGER", List.of( "ADMIN", "ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
        Map.entry("SUPERVISOR", List.of( "ADMIN", "ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT")),
        Map.entry("ACCOUNTANT", List.of("ACCOUNT", "PROCESS", "PAYMENT", "REPORT")),
        Map.entry("AUDIT", List.of("PAYMENT", "REPORT", "MAINTENANCE")),
        Map.entry("CUSTOMER_SERVICE", List.of( "ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT"))
    );

    private final ObjectMapper objectMapper;

    public PermissionServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> resolveAdminModuleKeys(Admin admin, Tenant tenant) {
        if (admin == null) {
            return List.of();
        }

        if (isC168Account(tenant)) {
            return LIST_FULL_PERMISSIONS;
        }

        if (isGroupGameTenant(tenant)) {
            return GROUP_FULL_PERMISSIONS;
        }

        if (isCompanyWithGameOrBank(tenant)) {
            final Set<String> template = new LinkedHashSet<>(companyTemplateKeys());
            final Set<String> roleKeys = new LinkedHashSet<>(roleModuleKeys(admin));
            final List<String> userKeys = parseUserPermissionKeys(admin.getPermissions());

            Set<String> effective = intersect(template, roleKeys);
            if (!userKeys.isEmpty()) {
                effective = intersect(effective, new LinkedHashSet<>(userKeys));
            }
            return orderKeys(effective);
        }
        return orderKeys(new LinkedHashSet<>(companyTemplateKeys()));
    }

    @Override
    public List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant) {
        if (isC168Account(tenant)) {
            return LIST_FULL_PERMISSIONS;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasGamblingModule(Tenant tenant) {
        return moduleCodes(tenant).stream().anyMatch(this::isGameCode);
    }

    @Override
    public boolean hasBankModule(Tenant tenant) {
        return moduleCodes(tenant).stream().anyMatch(this::isBankCode);
    }



    @Override
    public boolean isC168Account(Tenant tenant) {
        if (tenant == null || tenant.getCode() == null) {
            return false;
        }
        return "C168".equals(tenant.getCode().trim().toUpperCase());
    }

    private boolean isGroupGameTenant(Tenant tenant) {
        return tenant != null && tenant.getTenantType() == TenantType.GROUP && hasGamblingModule(tenant);
    }

    private boolean isCompanyWithGameOrBank(Tenant tenant) {
        return tenant != null && tenant.getTenantType() == TenantType.COMPANY && (hasGamblingModule(tenant) || hasBankModule(tenant));
    }

    /** COMPANY sidebar base template (no ownership). */
    private static List<String> companyTemplateKeys() {
        return LIST_FULL_PERMISSIONS.stream()
                .filter(key -> !"OWNERSHIP".equals(key))
                .toList();
    }

    private List<String> roleModuleKeys(Admin admin) {
        final String role = normalizeRole(admin);
        final List<String> keys = ROLE_PERMISSION_MAP.get(role);
        return keys != null ? keys : List.of();
    }

    private List<String> parseUserPermissionKeys(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.isBlank()) {
            return List.of();
        }

        try {
            final List<String> parsed = objectMapper.readValue(permissionsJson, new TypeReference<>() {});
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }

            return parsed.stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.trim().toUpperCase())
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<String> orderKeys(Set<String> keys) {
        final List<String> ordered = new ArrayList<>();
        for (String key : LIST_FULL_PERMISSIONS) {
            if (keys.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        final Set<String> result = new LinkedHashSet<>();
        for (String key : left) {
            if (right.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private static String normalizeRole(Admin admin) {
        if (admin.getRole() == null) {
            return "";
        }
        return admin.getRole().name();
    }

    private static List<String> moduleCodes(Tenant tenant) {
        if (tenant == null || tenant.getFeatureModules() == null) {
            return List.of();
        }
        return tenant.getFeatureModules();
    }

    private boolean isGameCode(String code) {
        return code != null && "GAME".equals(code.trim().toUpperCase());
    }

    private boolean isBankCode(String code) {
        return code != null && "BANK".equals(code.trim().toUpperCase());
    }
}


