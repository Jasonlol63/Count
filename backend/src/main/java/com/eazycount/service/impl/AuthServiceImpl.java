package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AuthDao;
import com.eazycount.dto.*;
import com.eazycount.entity.Admin;
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
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;

@Service
public class AuthServiceImpl implements AuthService {

    private final AuthDao authDao;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenStore authTokenStore;
    private final JwtService jwtService;

    public AuthServiceImpl(
            AuthDao authDao,
            PasswordEncoder passwordEncoder,
            AuthTokenStore authTokenStore,
            JwtService jwtService
    ) {
        this.authDao = authDao;
        this.passwordEncoder = passwordEncoder;
        this.authTokenStore = authTokenStore;
        this.jwtService = jwtService;
    }

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
            User member = authDao.findMemberByAccountId(name);
            if (member == null || !verifyPassword(password, member.getPassword())) {
                throw new BusinessException("Account ID, Company ID or password is incorrect");
            }
            List<UserTenantDTO> access = findAccessibleTenantsByMemberId(member.getId(), code);
            if (access.isEmpty()) {
                throw new BusinessException("You do not have access to this Company or Group");
            }
            Tenant sessionTenant = access.get(0).getTenant();
            assertTenantNotExpired(sessionTenant);
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

        Tenant sessionTenant = result.getSessionTenant();
        Tenant loginTenant = result.getLoginTenant();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("tenant", toTenantMap(sessionTenant));
        body.put("login_tenant", toTenantMap(loginTenant));
        body.put("user_type", result.getUserType());
        body.put("redirect", result.getRedirect());
        return body;
    }

    private LoginResultDTO buildLoginResult(UserDTO identity, Tenant loginTenant, Tenant sessionTenant) {
        LoginResultDTO result = new LoginResultDTO();
        result.setIdentity(identity);
        result.setLoginTenant(loginTenant);
        result.setSessionTenant(sessionTenant);

        if (identity.getUser() != null) {
            result.setUserType("member");
            result.setRedirect("/member");
            return result;
        }
        if (identity.getAdmin() != null) {
            result.setUserType("user");
            result.setRedirect(needsUserSecondaryPassword(identity.getAdmin(), sessionTenant)
                    ? "/user-secondary-password"
                    : "/dashboard");
            return result;
        }
        if (identity.getOwner() != null) {
            result.setUserType("owner");
            result.setRedirect("/owner-secondary-password");
            return result;
        }

        throw new BusinessException("Invalid login result");
    }

    private static boolean needsUserSecondaryPassword(Admin admin, Tenant tenant) {
        if (admin == null || tenant == null) {
            return false;
        }
        boolean isC168 = "C168".equalsIgnoreCase(Objects.toString(tenant.getCode(), ""));
        boolean hasSecondary = admin.getSecondaryPassword() != null
                && !admin.getSecondaryPassword().isBlank();
        return isC168 && hasSecondary;
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
    public Owner findOwnerByOwnerCode(String ownerCode) {
        Owner owner = authDao.findOwnerByOwnerCode(normalize(ownerCode));
        if (owner == null || owner.getId() == null) {
            throw new BusinessException("Owner Not Found!");
        }
        return owner;
    }

    @Override
    public Admin findAdminByLoginId(String loginId) {
        Admin admin = authDao.findAdminByLoginId(normalize(loginId));
        if (admin == null || admin.getLoginId() == null) {
            throw new BusinessException("Admin Not Found!");
        }
        return admin;
    }

    @Override
    public User findMemberByAccountId(String accountId) {
        User member = authDao.findMemberByAccountId(normalize(accountId));
        if (member == null || member.getAccountId() == null) {
            throw new BusinessException("User Not Found!");
        }
        return member;
    }

    @Override
    public List<Tenant> findActiveTenantsByLoginCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("Invalid Group/Company ID");
        }
        List<Tenant> tenants = authDao.findActiveTenantsByLoginCode(normalize(tenantCode));
        if (tenants.isEmpty()) {
            throw new BusinessException("Group/Company Not Found!");
        }
        return tenants;
    }

    @Override
    public List<TenantListDTO> findAllTenantsByOwnerId(Integer ownerId) {
        if (ownerId == null) {
            throw new BusinessException("Invalid Login!");
        }
        return authDao.findAllTenantsByOwnerId(ownerId); // 空就返回 []
    }

    @Override
    public List<TenantListDTO> findAllTenantsByAdminId(Integer adminId) {
        if (adminId == null) {
            throw new BusinessException("Invalid Login!");
        }
        return authDao.findAllTenantsByAdminId(adminId); // 空就返回 []
    }

    @Override
    public List<TenantListDTO> findAllTenantsByMemberId(Integer userId) {
        if (userId == null) {
            throw new BusinessException("Invalid Login!");
        }
        return authDao.findAllTenantsByMemberId(userId); // 空就返回 []
    }

    @Override
    public Map<String, Object> accessibleTenants(boolean all) {
        SessionUser user = SecurityUtils.currentUser();
        if (user == null || user.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        String userType = String.valueOf(user.user_type).trim().toLowerCase();
        List<TenantListDTO> rows = switch (userType) {
            case "owner" -> findAllTenantsByOwnerId(user.user_id);
            case "member" -> findAllTenantsByMemberId(user.user_id);
            default -> findAllTenantsByAdminId(user.user_id); // admin tab → user_type = "user"
        };

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> data = new ArrayList<>();
        LinkedHashSet<String> parentTenantCodes = new LinkedHashSet<>();

        for (TenantListDTO row : rows) {
            if (row == null) {
                continue;
            }
            if (row.getExpirationDate() != null && row.getExpirationDate().isBefore(today)) {
                continue;
            }
            if (!all) {
                // 预留：按 session tenant_id / login_scope 过滤
            }

            boolean isGroup = row.getTenantType() == Tenant.TenantType.GROUP;
            String code = row.getCode() != null ? row.getCode().trim().toUpperCase() : "";
            String parentTenantCode = row.getParentGroupCode() != null
                    ? row.getParentGroupCode().trim().toUpperCase()
                    : null;

            Map<String, Object> ui = new LinkedHashMap<>();
            ui.put("tenant_id", row.getId());
            ui.put("tenant_code", code);
            ui.put("tenant_type", row.getTenantType() != null ? row.getTenantType().name() : null);
            ui.put("parent_tenant_code", isGroup ? code : parentTenantCode);
            ui.put("native_parent_tenant_code", isGroup ? code : parentTenantCode);
            ui.put("expiration_date", row.getExpirationDate());
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

    /**
     * Resolves the tenant the user typed in the login form.
     * Prefers a direct GROUP match, then direct COMPANY, then group context for subsidiary matches.
     */
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

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toUpperCase();
    }


    @Override
    public SessionUser applyInitialSecondaryState(SessionUser sessionUser, LoginResultDTO result) {
        if (result.getIdentity().getOwner() != null) {
            // owner 始终 needs_owner_secondary=true（由 SessionUser.fromOwner 保证）
            return sessionUser;
        }
        if (result.getIdentity().getAdmin() != null) {
            Admin admin = result.getIdentity().getAdmin();
            Tenant tenant = result.getSessionTenant();
            if (!needsUserSecondaryPassword(admin, tenant)) {
                return sessionUser.withSecondaryVerified();
            }
        }
        return sessionUser;
    }

    @Override
    public void verifyOwnerSecondaryPassword(
            String secondaryPassword,
            SessionUser current,
            String jti,
            long ttlMillis
    ) {
        if (current == null || !"owner".equalsIgnoreCase(current.user_type)) {
            throw new BusinessException("Unauthorized");
        }
        if (!current.needs_owner_secondary) {
            return; // Already verified
        }

        String pin = secondaryPassword == null ? "" : secondaryPassword.trim();
        if (pin.isEmpty()) {
            throw new BusinessException("Please enter secondary password");
        }
        if (!pin.matches("^\\d{6}$")) {
            throw new BusinessException("Secondary password must be exactly 6 digits");
        }

        Owner row = authDao.findOwnerSecondaryPasswordById(current.user_id);
        String storedHash = row != null ? row.getSecondaryPassword() : null;

        if (storedHash != null && !storedHash.isBlank() && !verifyPassword(pin, storedHash)) {
            throw new BusinessException("Secondary password is incorrect");
        }

        authTokenStore.save(jti, current.withSecondaryVerified(), ttlMillis);
    }

    @Override
    public void verifyUserSecondaryPassword(
            String secondaryPassword,
            SessionUser current,
            String jti,
            long ttlMillis
    ) {
        if (current == null || !"user".equalsIgnoreCase(current.user_type)) {
            throw new BusinessException("Unauthorized");
        }

        // 非 C168：直接标记通过
        if (!current.is_current_tenant_c168) {
            authTokenStore.save(jti, current.withSecondaryVerified(), ttlMillis);
            return;
        }

        if (!current.needs_user_secondary) {
            return; // 已验证或本来就不需要
        }

        String pin = secondaryPassword == null ? "" : secondaryPassword.trim();
        if (pin.isEmpty()) {
            throw new BusinessException("Please enter secondary password");
        }
        if (!pin.matches("^\\d{6}$")) {
            throw new BusinessException("Secondary password must be exactly 6 digits");
        }

        Admin row = authDao.findAdminSecondaryPasswordById(current.user_id);
        String storedHash = row != null ? row.getSecondaryPassword() : null;

        if (storedHash != null && !storedHash.isBlank() && !verifyPassword(pin, storedHash)) {
            throw new BusinessException("Secondary password is incorrect");
        }

        authTokenStore.save(jti, current.withSecondaryVerified(), ttlMillis);
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
}
