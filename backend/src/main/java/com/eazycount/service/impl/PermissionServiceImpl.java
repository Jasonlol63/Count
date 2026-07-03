package com.eazycount.service.impl;

import com.eazycount.dao.PermissionDao;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Tenant.TenantType;
import com.eazycount.service.PermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PermissionServiceImpl implements PermissionService {

    private static final String GAME_CODE = "GAME";
    private static final String BANK_CODE = "BANK";

    /** Internal module keys (uppercase); order matches frontend {@code PERMISSION_KEYS}. */
    private static final List<String> LIST_FULL_PERMISSIONS = List.of(
            "HOME", "DOMAIN", "ANNOUNCEMENTS", "AUTORENEW", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS",
            "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE"
    );

    /** GROUP: fixed sidebar for all roles — no {@code PROCESS}, no role/JSON narrowing. */
    private static final List<String> GROUP_FULL_PERMISSIONS = List.of(
            "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE"
    );

    private static final List<String> GAME_FULL_PERMISSIONS = List.of(
            "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE"
    );

    private static final List<String> BANK_FULL_PERMISSIONS = List.of(
            "HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "MAINTENANCE"
    );

    private static final Map<String, List<String>> ROLE_PERMISSION_MAP = Map.ofEntries(
            Map.entry("PARTNERSHIP", List.of("HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
            Map.entry("ADMIN", List.of("HOME", "ADMIN", "ACCOUNT", "OWNERSHIP", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
            Map.entry("MANAGER", List.of("ADMIN", "ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT", "MAINTENANCE")),
            Map.entry("SUPERVISOR", List.of("ADMIN", "ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT")),
            Map.entry("ACCOUNTANT", List.of("ACCOUNT", "PROCESS", "PAYMENT", "REPORT")),
            Map.entry("AUDIT", List.of("PAYMENT", "REPORT", "MAINTENANCE")),
            Map.entry("CUSTOMER_SERVICE", List.of("ACCOUNT", "PROCESS", "DATACAPTURE", "PAYMENT", "REPORT"))
    );

    private final PermissionDao permissionDao;
    private final ObjectMapper objectMapper;

    public PermissionServiceImpl(PermissionDao permissionDao, ObjectMapper objectMapper) {
        this.permissionDao = permissionDao;
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

        if (isGroupTenant(tenant)) {
            return GROUP_FULL_PERMISSIONS;
        }

        if (isCompanyWithGameOrBank(tenant)) {
            Set<String> template = new LinkedHashSet<>(companyTemplateKeys(tenant));
            Set<String> roleKeys = new LinkedHashSet<>(roleModuleKeys(admin));
            Set<String> effective = intersect(template, roleKeys);

            List<String> userKeys = parseUserPermissionKeys(admin.getPermissions());
            if (!userKeys.isEmpty()) {
                effective = intersect(effective, new LinkedHashSet<>(userKeys));
            }
            return new ArrayList<>(effective);
        }

        return roleModuleKeys(admin);
    }

    @Override
    public List<String> resolveOwnerModuleKeys(Owner owner, Tenant tenant) {
        if (owner == null) {
            return List.of();
        }
        if (isC168Account(tenant)) {
            return LIST_FULL_PERMISSIONS;
        }
        return LIST_FULL_PERMISSIONS;
    }

    @Override
    public boolean hasGameModule(Tenant tenant) {
        if (tenant != null && tenant.getTenantType() == TenantType.GROUP) {
            return true;
        }
        return effectiveCategoryCodes(tenant).contains(GAME_CODE);
    }

    @Override
    public boolean hasBankModule(Tenant tenant) {
        if (tenant != null && tenant.getTenantType() == TenantType.GROUP) {
            return false;
        }
        return effectiveCategoryCodes(tenant).contains(BANK_CODE);
    }

    @Override
    public boolean isC168Account(Tenant tenant) {
        if (tenant == null || tenant.getCode() == null) {
            return false;
        }
        return "C168".equalsIgnoreCase(tenant.getCode().trim());
    }

    private boolean isGroupTenant(Tenant tenant) {
        return tenant != null && tenant.getTenantType() == TenantType.GROUP;
    }

    private boolean isCompanyWithGameOrBank(Tenant tenant) {
        return tenant != null
                && tenant.getTenantType() == TenantType.COMPANY
                && (hasGameModule(tenant) || hasBankModule(tenant));
    }

    private List<String> companyTemplateKeys(Tenant tenant) {
        boolean hasGame = hasGameModule(tenant);
        boolean hasBank = hasBankModule(tenant);

        if (hasGame && !hasBank) {
            return GAME_FULL_PERMISSIONS;
        }
        if (hasBank && !hasGame) {
            return BANK_FULL_PERMISSIONS;
        }

        return LIST_FULL_PERMISSIONS.stream()
                .filter(key -> !"OWNERSHIP".equals(key))
                .toList();
    }

    private List<String> roleModuleKeys(Admin admin) {
        String role = admin.getRole() == null ? "" : admin.getRole().name();
        List<String> keys = ROLE_PERMISSION_MAP.get(role);
        return keys != null ? keys : List.of();
    }

    private List<String> parseUserPermissionKeys(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(permissionsJson, new TypeReference<>() {});
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

    /** COMPANY only; GROUP flags are fixed in {@link #hasGameModule} / {@link #hasBankModule}. */
    private Set<String> effectiveCategoryCodes(Tenant tenant) {
        if (tenant == null || tenant.getTenantType() != TenantType.COMPANY) {
            return Set.of();
        }

        LinkedHashSet<String> codes = new LinkedHashSet<>(normalizeCategoryCodes(tenant.getCategoryCode()));

        if (codes.isEmpty() && tenant.getId() != null) {
            Tenant fresh = permissionDao.findTenantById(tenant.getId());
            if (fresh != null) {
                codes.addAll(normalizeCategoryCodes(fresh.getCategoryCode()));
            }
        }

        return codes;
    }

    private static List<String> normalizeCategoryCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return codes.stream()
                .filter(Objects::nonNull)
                .map(code -> code.trim().toUpperCase())
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : left) {
            if (right.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }
}
