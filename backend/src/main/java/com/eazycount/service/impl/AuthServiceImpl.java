package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AuthDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.AdminTenantDTO;
import com.eazycount.dto.LoginResultDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.TenantDTO;
import com.eazycount.dto.UserDTO;
import com.eazycount.dto.UserTenantDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import com.eazycount.jwt.JwtService;
import com.eazycount.security.AuthCookieHelper;
import com.eazycount.security.AuthTokenStore;
import com.eazycount.security.LoginUserPrincipal;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AuthService;
import com.eazycount.service.LoginRole;
import com.eazycount.service.PermissionService;
import com.eazycount.util.TenantDtoHelper;
import com.eazycount.util.SecondaryPasswordUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private AuthDao authDao;
    @Autowired
    private TenantDao tenantDao;
    @Autowired
    private PermissionService permissionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthTokenStore authTokenStore;
    @Autowired
    private JwtService jwtService;

    @Override
    public LoginResultDTO login(String tenantCode, String username, String password, LoginRole role) {
        List<Tenant> loginCandidates = findActiveTenantsByLoginCode(tenantCode);
        Tenant loginTenant = resolveLoginTenant(loginCandidates, tenantCode);

        String code = normalize(tenantCode);
        String name = normalize(username);

        if (name.isBlank()) {
            throw new BusinessException("Please enter username");
        }
        if (role == null) {
            throw new BusinessException("Invalid login role");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException("Password is required");
        }

        UserDTO identity = new UserDTO();

        if (role == LoginRole.MEMBER) {
            User member = requireIdentity("member", name).getUser();
            if (!verifyPassword(password, member.getPassword())) {
                throw new BusinessException("Account ID, Company ID or password is incorrect");
            }
            List<UserTenantDTO> access = findAccessibleTenantsByMemberId(member.getId(), code);
            if (access.isEmpty()) {
                throw new BusinessException("You do not have access to this Company or Group");
            }
            Tenant sessionTenant = access.get(0).getTenant();
            assertTenantNotExpired(sessionTenant);
            authDao.updateMemberLastLogin(member.getId());
            identity.setUser(member);
            identity.setTenant(sessionTenant);
            return buildLoginResult(identity, loginTenant, sessionTenant);
        }

        Admin admin = authDao.findAdminByLoginId(name);
        if (admin != null) {
            if (!verifyPassword(password, admin.getPassword())) {
                throw new BusinessException("Username or password is incorrect");
            }
            List<AdminTenantDTO> access = findAccessibleTenantsByAdminId(admin.getId(), code);
            if (access.isEmpty()) {
                throw new BusinessException("You do not have access to this Company or Group");
            }
            Tenant sessionTenant = access.get(0).getTenant();
            assertTenantNotExpired(sessionTenant);
            authDao.updateAdminLastLogin(admin.getId());
            identity.setAdmin(admin);
            identity.setTenant(sessionTenant);
            return buildLoginResult(identity, loginTenant, sessionTenant);
        }

        Owner owner = authDao.findOwnerByOwnerCode(name);
        if (owner != null) {
            if (!verifyPassword(password, owner.getPassword())) {
                throw new BusinessException("Username or password is incorrect");
            }
            List<OwnerTenantDTO> access = findAccessibleTenantsByOwnerId(owner.getId(), code);
            if (access.isEmpty()) {
                throw new BusinessException("You do not have access to this Company or Group");
            }
            Tenant sessionTenant = access.get(0).getTenant();
            assertTenantNotExpired(sessionTenant);
            identity.setOwner(owner);
            identity.setTenant(sessionTenant);
            return buildLoginResult(identity, loginTenant, sessionTenant);
        }

        throw new BusinessException("Username or password is incorrect");
    }

    @Override
    public Map<String, Object> toLoginResponse(LoginResultDTO result) {
        if (result == null || result.getIdentity() == null) {
            throw new BusinessException("Invalid login result");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("tenant", toTenantMap(result.getSessionTenant()));
        body.put("login_tenant", toTenantMap(result.getLoginTenant()));
        body.put("user_type", result.getUserType());
        body.put("redirect", result.getRedirect());
        return body;
    }

    @Override
    public List<OwnerTenantDTO> findAccessibleTenantsByOwnerId(Integer ownerId, String tenantCode) {
        if (ownerId == null || tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("Invalid to Login!");
        }
        return authDao.findAccessibleTenantsByOwnerId(ownerId, normalize(tenantCode));
    }

    @Override
    public List<AdminTenantDTO> findAccessibleTenantsByAdminId(Integer adminId, String tenantCode) {
        if (adminId == null || tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("Invalid to Login!");
        }
        return authDao.findAccessibleTenantsByAdminId(adminId, normalize(tenantCode));
    }

    @Override
    public List<UserTenantDTO> findAccessibleTenantsByMemberId(Integer userId, String tenantCode) {
        if (userId == null || tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("Invalid to Login!");
        }
        return authDao.findAccessibleTenantsByMemberId(userId, normalize(tenantCode));
    }

    @Override
    public UserDTO requireIdentity(String userType, String identifier) {
        if (userType == null || userType.isBlank()) {
            throw new BusinessException("Invalid identity type");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new BusinessException("Invalid identity identifier");
        }

        String key = normalize(identifier);
        UserDTO identity = new UserDTO();

        return switch (userType.trim().toLowerCase()) {
            case "member" -> {
                identity.setUser(requireFound(
                        authDao.findMemberByAccountId(key),
                        User::getAccountId,
                        "User Not Found!"));
                yield identity;
            }
            case "user" -> {
                identity.setAdmin(requireFound(
                        authDao.findAdminByLoginId(key),
                        Admin::getLoginId,
                        "Admin Not Found!"));
                yield identity;
            }
            case "owner" -> {
                identity.setOwner(requireFound(
                        authDao.findOwnerByOwnerCode(key),
                        Owner::getId,
                        "Owner Not Found!"));
                yield identity;
            }
            default -> throw new BusinessException("Invalid identity type");
        };
    }

    @Override
    public List<Tenant> findActiveTenantsByLoginCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("Invalid Group/Company ID");
        }
        List<Tenant> tenants = TenantDtoHelper.distinctTenants(
                tenantDao.findActiveTenantFeaturesByLoginCode(normalize(tenantCode)));
        if (tenants.isEmpty()) {
            throw new BusinessException("Group/Company Not Found!");
        }
        return tenants;
    }

    @Override
    public List<TenantDTO> findAllTenantsByUserType(String userType, Integer userId) {
        if (userId == null) {
            throw new BusinessException("Invalid Login!");
        }
        if (userType == null || userType.isBlank()) {
            throw new BusinessException("Invalid identity type");
        }

        return switch (userType.trim().toLowerCase()) {
            case "owner" -> tenantDao.findTenantFeaturesByOwnerId(userId);
            case "member" -> tenantDao.findTenantFeaturesByMemberId(userId);
            case "user" -> tenantDao.findTenantFeaturesByAdminId(userId);
            default -> throw new BusinessException("Invalid identity type");
        };
    }

    @Override
    public Map<String, Object> accessibleTenants(boolean all) {
        SessionUser user = SecurityUtils.currentUser();
        if (user == null || user.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        String userType = String.valueOf(user.user_type).trim().toLowerCase();
        List<TenantDTO> rows = findAllTenantsByUserType(userType, user.user_id);

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> data = new ArrayList<>();
        LinkedHashSet<String> parentTenantCodes = new LinkedHashSet<>();

        for (Tenant tenant : TenantDtoHelper.distinctTenants(rows)) {
            if (tenant == null) {
                continue;
            }
            if (tenant.getExpirationDate() != null && tenant.getExpirationDate().isBefore(today)) {
                continue;
            }
            if (!all) {
                // Reserved: filter by session tenant_id / login_scope when needed.
            }

            boolean isGroup = tenant.getTenantType() == Tenant.TenantType.GROUP;
            String code = tenant.getCode() != null ? tenant.getCode().trim().toUpperCase() : "";
            String parentTenantCode = tenant.getParentGroupCode() != null
                    ? tenant.getParentGroupCode().trim().toUpperCase()
                    : null;

            Map<String, Object> ui = new LinkedHashMap<>();
            ui.put("tenant_id", tenant.getId());
            ui.put("tenant_code", code);
            ui.put("tenant_type", tenant.getTenantType() != null ? tenant.getTenantType().name() : null);
            ui.put("parent_tenant_code", isGroup ? code : parentTenantCode);
            ui.put("native_parent_tenant_code", isGroup ? code : parentTenantCode);
            ui.put("expiration_date", tenant.getExpirationDate());
            data.add(ui);

            if (isGroup && !code.isBlank()) {
                parentTenantCodes.add(code);
            } else if (parentTenantCode != null && !parentTenantCode.isBlank()) {
                parentTenantCodes.add(parentTenantCode);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "");
        body.put("data", data);
        body.put("accessible_parent_tenant_codes", new ArrayList<>(parentTenantCodes));
        return body;
    }

    @Override
    public SessionUser applyInitialSecondaryState(SessionUser sessionUser, LoginResultDTO result) {
        if (result.getIdentity().getOwner() != null) {
            if (!sessionUser.needs_owner_secondary) {
                return sessionUser.withSecondaryVerified();
            }
            return sessionUser;
        }
        if (result.getIdentity().getAdmin() != null && !sessionUser.needs_user_secondary) {
            return sessionUser.withSecondaryVerified();
        }
        return sessionUser;
    }

    @Override
    public void verifySecondaryPassword(String secondaryPassword, SessionUser current, String jti, long ttlMillis) {
        if (current == null || current.user_id == null) {
            throw new BusinessException("Unauthorized");
        }

        String userType = String.valueOf(current.user_type).trim().toLowerCase();
        if ("owner".equals(userType)) {
            if (!current.needs_owner_secondary) {
                return;
            }
            verifySecondaryPin(secondaryPassword, current.user_id, true);
        } else if ("user".equals(userType)) {
            if (!current.needs_user_secondary) {
                return;
            }
            verifySecondaryPin(secondaryPassword, current.user_id, false);
        } else {
            throw new BusinessException("Unauthorized");
        }

        authTokenStore.save(jti, current.withSecondaryVerified(), ttlMillis);
    }

    @Override
    public Map<String, Object> switchSessionTenant(int tenantId, SessionUser current, String jti, long ttlMillis) {
        if (current == null || current.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId <= 0) {
            throw new BusinessException("Missing tenant_id parameter");
        }
        if (!userCanAccessTenantId(current, tenantId)) {
            throw new BusinessException("No permission to access this company");
        }

        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null) {
            throw new BusinessException("No permission to access this company");
        }
        assertTenantNotExpired(tenant);

        List<FeatureModule> featureModules = tenantDao.findActiveFeatureModulesByTenantId(tenantId);
        SessionUser rebuilt = rebuildSessionUserWithTenant(current, tenant, featureModules);
        final boolean secondaryAlreadyVerified =
                !current.needs_owner_secondary && !current.needs_user_secondary;
        if (secondaryAlreadyVerified || (!rebuilt.needs_owner_secondary && !rebuilt.needs_user_secondary)) {
            rebuilt = rebuilt.withSecondaryVerified();
        }

        authTokenStore.save(jti, rebuilt, ttlMillis);

        final boolean hasGame = permissionService.hasGameModule(featureModules);
        final boolean hasBank = permissionService.hasBankModule(featureModules);
        final String code = tenant.getCode() != null ? tenant.getCode().trim().toUpperCase() : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant_id", tenant.getId());
        data.put("tenant_code", code);
        data.put("has_game", hasGame);
        data.put("has_bank", hasBank);
        data.put("company_id", tenant.getId());
        data.put("company_code", code);
        data.put("has_gambling", hasGame);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Company updated");
        body.put("error", null);
        body.put("data", data);
        return body;
    }

    @Override
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String jti = resolveLogoutJti(request);
        if (StringUtils.hasText(jti)) {
            authTokenStore.delete(jti);
        }

        AuthCookieHelper.clearAccessTokenCookie(response, jwtService);
        SecurityContextHolder.clearContext();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Logged out");
        return body;
    }

    private LoginResultDTO buildLoginResult(UserDTO identity, Tenant loginTenant, Tenant sessionTenant) {
        LoginResultDTO result = new LoginResultDTO();
        result.setIdentity(identity);
        result.setLoginTenant(loginTenant);
        result.setSessionTenant(sessionTenant);
        if (sessionTenant != null && sessionTenant.getId() != null) {
            result.setSessionFeatureModules(
                    tenantDao.findActiveFeatureModulesByTenantId(sessionTenant.getId()));
        } else {
            result.setSessionFeatureModules(List.of());
        }

        if (identity.getUser() != null) {
            result.setUserType("member");
            result.setRedirect("/member");
            return result;
        }
        if (identity.getAdmin() != null) {
            result.setUserType("user");
            result.setRedirect(SecondaryPasswordUtils.isConfigured(identity.getAdmin().getSecondaryPassword())
                    ? "/user-secondary-password"
                    : "/dashboard");
            return result;
        }
        if (identity.getOwner() != null) {
            result.setUserType("owner");
            result.setRedirect(SecondaryPasswordUtils.isConfigured(identity.getOwner().getSecondaryPassword())
                    ? "/owner-secondary-password"
                    : "/dashboard");
            return result;
        }

        throw new BusinessException("Invalid login result");
    }

    private void verifySecondaryPin(String secondaryPassword, Integer userId, boolean owner) {
        String pin = secondaryPassword == null ? "" : secondaryPassword.trim();
        if (pin.isEmpty()) {
            throw new BusinessException("Please enter secondary password");
        }
        if (!pin.matches("^\\d{6}$")) {
            throw new BusinessException("Secondary password must be exactly 6 digits");
        }

        String storedHash;
        if (owner) {
            Owner row = authDao.findOwnerSecondaryPasswordById(userId);
            storedHash = row != null ? row.getSecondaryPassword() : null;
        } else {
            Admin row = authDao.findAdminSecondaryPasswordById(userId);
            storedHash = row != null ? row.getSecondaryPassword() : null;
        }

        if (storedHash != null && !storedHash.isBlank() && !verifyPassword(pin, storedHash)) {
            throw new BusinessException("Secondary password is incorrect");
        }
    }

    private static Map<String, Object> toTenantMap(Tenant tenant) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (tenant == null) {
            return map;
        }
        map.put("id", tenant.getId());
        map.put("code", tenant.getCode());
        map.put("type", tenant.getTenantType() != null ? tenant.getTenantType().name() : null);
        map.put("name", tenant.getName());
        map.put("parent_id", tenant.getParentId());
        String parentCode = tenant.getParentGroupCode();
        map.put("parent_code", parentCode != null && !parentCode.isBlank()
                ? parentCode.trim().toUpperCase()
                : null);
        return map;
    }

    private Tenant resolveLoginTenant(List<Tenant> candidates, String tenantCodeInput) {
        String code = normalize(tenantCodeInput);

        for (Tenant tenant : candidates) {
            if (code.equalsIgnoreCase(Objects.toString(tenant.getCode(), ""))
                    && tenant.getTenantType() == Tenant.TenantType.GROUP) {
                return tenant;
            }
        }

        for (Tenant tenant : candidates) {
            if (code.equalsIgnoreCase(Objects.toString(tenant.getCode(), ""))) {
                return tenant;
            }
        }

        Tenant groupContext = new Tenant();
        groupContext.setCode(code);
        groupContext.setName(code);
        groupContext.setTenantType(Tenant.TenantType.GROUP);
        return groupContext;
    }

    private boolean userCanAccessTenantId(SessionUser user, int tenantId) {
        if (user == null || user.user_id == null) {
            return false;
        }
        final String userType = String.valueOf(user.user_type).trim().toLowerCase();
        final List<TenantDTO> rows = findAllTenantsByUserType(userType, user.user_id);
        final LocalDate today = LocalDate.now();
        for (TenantDTO row : rows) {
            if (row == null || row.getTenant() == null || row.getTenant().getId() == null) {
                continue;
            }
            if (row.getTenant().getId() != tenantId) {
                continue;
            }
            if (row.getTenant().getExpirationDate() != null
                    && row.getTenant().getExpirationDate().isBefore(today)) {
                return false;
            }
            return true;
        }
        return false;
    }

    private SessionUser rebuildSessionUserWithTenant(
            SessionUser current,
            Tenant tenant,
            List<FeatureModule> featureModules
    ) {
        final String userType = String.valueOf(current.user_type).trim().toLowerCase();
        UserDTO identity = requireIdentity(userType, current.login_id);
        identity.setTenant(tenant);
        return SessionUser.from(identity, tenant, featureModules, permissionService);
    }

    private static <T> T requireFound(T entity, Function<T, ?> key, String notFoundMessage) {
        if (entity == null || key.apply(entity) == null) {
            throw new BusinessException(notFoundMessage);
        }
        return entity;
    }

    private boolean verifyPassword(String raw, String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return passwordEncoder.matches(raw, stored);
        }
        return raw.equals(stored);
    }

    private void assertTenantNotExpired(Tenant tenant) {
        if (tenant == null) {
            throw new BusinessException("Invalid tenant");
        }
        if (tenant.getExpirationDate() != null
                && tenant.getExpirationDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Company or Group has expired.");
        }
    }

    private String resolveLogoutJti(HttpServletRequest request) {
        return SecurityUtils.currentPrincipal()
                .map(LoginUserPrincipal::jti)
                .orElseGet(() -> extractJtiFromRequest(request));
    }

    private String extractJtiFromRequest(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }

        final String cookieName = jwtService.getCookieName();
        for (Cookie cookie : request.getCookies()) {
            if (!cookieName.equals(cookie.getName()) || !StringUtils.hasText(cookie.getValue())) {
                continue;
            }
            try {
                Claims claims = jwtService.parseToken(cookie.getValue().trim());
                return claims.getId();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toUpperCase();
    }
}
