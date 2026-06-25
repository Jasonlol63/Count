package com.eazycount.security;

import com.eazycount.dto.UserDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import com.eazycount.service.PermissionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Authenticated user payload — aligned with legacy {@code current_user_api.php}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionUser implements Serializable {

    public static final String SESSION_KEY = "EC_SESSION_USER";

    public String user_type;
    public Integer user_id;
    public Integer tenant_id;
    public String tenant_code;
    public String login_scope;
    public String login_identifier;
    public boolean needs_owner_secondary;
    public boolean needs_user_secondary;
    public String expiration_date;
    public String name;
    public String login_id;
    public String role;
    public List<String> permissions;
    public boolean is_current_tenant_c168;
    public boolean tenant_has_game;
    public boolean tenant_has_bank;
    public int read_only;

    public SessionUser() {
    }

    private SessionUser(
            String userType,
            Integer userId,
            Integer tenantId,
            String tenantCode,
            String loginScope,
            String loginIdentifier,
            boolean needsOwnerSecondary,
            boolean needsUserSecondary,
            String expirationDate,
            String name,
            String loginId,
            String role,
            List<String> permissions,
            boolean isCurrentTenantC168,
            boolean tenantHasGame,
            boolean tenantHasBank,
            int readOnly
    ) {
        this.user_type = userType;
        this.user_id = userId;
        this.tenant_id = tenantId;
        this.tenant_code = tenantCode;
        this.login_scope = loginScope;
        this.login_identifier = loginIdentifier;
        this.needs_owner_secondary = needsOwnerSecondary;
        this.needs_user_secondary = needsUserSecondary;
        this.expiration_date = expirationDate;
        this.name = name;
        this.login_id = loginId;
        this.role = role;
        this.permissions = permissions != null ? permissions : Collections.emptyList();
        this.is_current_tenant_c168 = isCurrentTenantC168;
        this.tenant_has_game = tenantHasGame;
        this.tenant_has_bank = tenantHasBank;
        this.read_only = readOnly;
    }

    public static SessionUser from(UserDTO dto, Tenant tenant, PermissionService permissionService) {
        if (dto == null) {
            throw new IllegalArgumentException("UserDTO is required");
        }
        if (permissionService == null) {
            throw new IllegalArgumentException("PermissionService is required");
        }

        final Tenant effectiveTenant = dto.getTenant() != null ? dto.getTenant() : tenant;

        if (dto.getAdmin() != null) {
            return fromAdmin(dto.getAdmin(), effectiveTenant, permissionService);
        }
        if (dto.getUser() != null) {
            return fromMember(dto.getUser(), effectiveTenant, permissionService);
        }
        if (dto.getOwner() != null) {
            return fromOwner(dto.getOwner(), effectiveTenant, permissionService);
        }

        throw new IllegalArgumentException("UserDTO has no identity");
    }

    public static SessionUser from(Owner owner, Tenant tenant, PermissionService permissionService) {
        if (owner == null) {
            throw new IllegalArgumentException("Owner is required");
        }
        if (permissionService == null) {
            throw new IllegalArgumentException("PermissionService is required");
        }
        return fromOwner(owner, tenant, permissionService);
    }

    private static SessionUser fromAdmin(Admin admin, Tenant tenant, PermissionService permissionService) {
        final String companyCode = tenantCode(tenant);
        final boolean isC168 = "C168".equalsIgnoreCase(companyCode);
        final boolean hasSecondary = admin.getSecondaryPassword() != null && !admin.getSecondaryPassword().isBlank();
        final String role = admin.getRole() != null ? admin.getRole().getValue() : "";
        final List<String> moduleKeys = toFrontendModuleKeys(
                permissionService.resolveAdminModuleKeys(admin, tenant));
        final boolean hasGame = permissionService.hasGameModule(tenant);
        final boolean hasBank = permissionService.hasBankModule(tenant);

        return new SessionUser(
                "user",
                admin.getId(),
                tenant != null ? tenant.getId() : null,
                blankToNull(companyCode),
                tenantScope(tenant),
                companyCode,
                false,
                isC168 && hasSecondary,
                tenantExpiration(tenant),
                Objects.toString(admin.getName(), ""),
                normalizeUpper(Objects.toString(admin.getLoginId(), "")),
                normalizeLower(role),
                moduleKeys,
                isC168,
                hasGame,
                hasBank,
                Boolean.TRUE.equals(admin.getReadOnly()) ? 1 : 0
        );
    }

    private static SessionUser fromMember(User member, Tenant tenant, PermissionService permissionService) {
        final String companyCode = tenantCode(tenant);
        final boolean hasGame = permissionService.hasGameModule(tenant);
        final boolean hasBank = permissionService.hasBankModule(tenant);

        return new SessionUser(
                "member",
                member.getId(),
                tenant != null ? tenant.getId() : null,
                blankToNull(companyCode),
                tenantScope(tenant),
                companyCode,
                false,
                false,
                tenantExpiration(tenant),
                Objects.toString(member.getName(), ""),
                normalizeUpper(Objects.toString(member.getAccountId(), "")),
                normalizeLower(Objects.toString(member.getRole(), "")),
                Collections.emptyList(),
                "C168".equalsIgnoreCase(companyCode),
                hasGame,
                hasBank,
                0
        );
    }

    private static SessionUser fromOwner(Owner owner, Tenant tenant, PermissionService permissionService) {
        final String companyCode = !tenantCode(tenant).isBlank()
                ? tenantCode(tenant)
                : normalizeUpper(Objects.toString(owner.getOwnerCode(), ""));
        final boolean hasGame = permissionService.hasGameModule(tenant);
        final boolean hasBank = permissionService.hasBankModule(tenant);

        return new SessionUser(
                "owner",
                owner.getId(),
                tenant != null ? tenant.getId() : null,
                blankToNull(companyCode),
                tenant != null ? tenantScope(tenant) : "group",
                companyCode,
                true,
                false,
                tenantExpiration(tenant),
                Objects.toString(owner.getName(), ""),
                normalizeUpper(Objects.toString(owner.getOwnerCode(), "")),
                "owner",
                toFrontendModuleKeys(permissionService.resolveOwnerModuleKeys(owner, tenant)),
                "C168".equalsIgnoreCase(companyCode),
                hasGame,
                hasBank,
                0
        );
    }

    public SessionUser withSecondaryVerified() {
        return new SessionUser(
                user_type,
                user_id,
                tenant_id,
                tenant_code,
                login_scope,
                login_identifier,
                false,
                false,
                expiration_date,
                name,
                login_id,
                role,
                permissions,
                is_current_tenant_c168,
                tenant_has_game,
                tenant_has_bank,
                read_only
        );
    }

    private static String tenantCode(Tenant tenant) {
        return tenant != null && tenant.getCode() != null
                ? normalizeUpper(tenant.getCode())
                : "";
    }

    private static String tenantScope(Tenant tenant) {
        return tenant != null && tenant.getTenantType() != null
                ? normalizeLower(tenant.getTenantType().getValue())
                : "company";
    }

    private static String tenantExpiration(Tenant tenant) {
        return tenant != null && tenant.getExpirationDate() != null
                ? tenant.getExpirationDate().toString()
                : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeUpper(String s) {
        return Objects.toString(s, "").trim().toUpperCase();
    }

    private static String normalizeLower(String s) {
        return Objects.toString(s, "").trim().toLowerCase();
    }

    /** Backend resolves uppercase module keys; frontend expects lowercase (e.g. {@code home}). */
    private static List<String> toFrontendModuleKeys(List<String> moduleKeys) {
        if (moduleKeys == null || moduleKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return moduleKeys.stream()
                .filter(Objects::nonNull)
                .map(key -> key.trim().toLowerCase())
                .toList();
    }
}
