package com.eazycount.service;

import com.eazycount.dto.AdminTenantDTO;
import com.eazycount.dto.LoginResultDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.TenantDTO;
import com.eazycount.dto.UserDTO;
import com.eazycount.dto.UserTenantDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

public interface AuthService {

    List<OwnerTenantDTO> findAccessibleTenantsByOwnerId(Integer ownerId, String tenantCode);

    List<AdminTenantDTO> findAccessibleTenantsByAdminId(Integer adminId, String tenantCode);

    List<UserTenantDTO> findAccessibleTenantsByMemberId(Integer userId, String tenantCode);

    /**
     * All tenants (with feature modules) visible to the given identity.
     *
     * @param userType {@code member} | {@code user} (admin) | {@code owner}
     * @param userId   primary key of account / user / owner
     */
    List<TenantDTO> findAllTenantsByUserType(String userType, Integer userId);

    Map<String, Object> accessibleTenants(boolean all);

    /**
     * Resolve a login identity by session/API user type.
     *
     * @param userType   {@code member} | {@code user} (admin) | {@code owner}
     * @param identifier account id, admin login id, or owner code
     */
    UserDTO requireIdentity(String userType, String identifier);

    List<Tenant> findActiveTenantsByLoginCode(String tenantCode);

    LoginResultDTO login(String tenantCode, String username, String password, LoginRole role);

    Map<String, Object> toLoginResponse(LoginResultDTO result);

    SessionUser applyInitialSecondaryState(SessionUser sessionUser, LoginResultDTO result);

    void verifySecondaryPassword(String secondaryPassword, SessionUser current, String jti, long ttlMillis);

    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response);

    Map<String, Object> switchSessionTenant(int tenantId, SessionUser current, String jti, long ttlMillis);
}
