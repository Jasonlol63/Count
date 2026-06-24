package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AuthDao;
import com.eazycount.dto.AdminTenantDTO;
import com.eazycount.dto.LoginResultDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.UserDTO;
import com.eazycount.dto.UserTenantDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import com.eazycount.service.AuthService;
import com.eazycount.service.LoginRole;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AuthServiceImpl implements AuthService {

    private final AuthDao authDao;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(AuthDao authDao, PasswordEncoder passwordEncoder) {
        this.authDao = authDao;
        this.passwordEncoder = passwordEncoder;
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("tenant", toTenantMap(result.getSessionTenant()));
        body.put("login_tenant", toTenantMap(result.getLoginTenant()));
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
}
