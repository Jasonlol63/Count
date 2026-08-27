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

    List<TenantDTO> findAllTenantsByUserType(String userType, Integer userId);

    Map<String, Object> accessibleTenants(boolean all);

    Map<String, Object> tenantByCode(String code);

    UserDTO requireIdentity(String userType, String identifier);

    List<Tenant> findActiveTenantsByLoginCode(String tenantCode);

    LoginResultDTO login(String tenantCode, String username, String password, LoginRole role);

    Map<String, Object> toLoginResponse(LoginResultDTO result);

    SessionUser applyInitialSecondaryState(SessionUser sessionUser, LoginResultDTO result);

    void verifySecondaryPassword(String secondaryPassword, SessionUser current, String jti, long ttlMillis);

    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response);

    Map<String, Object> switchSessionTenant(int tenantId, SessionUser current, String jti, long ttlMillis);

    //Sends a password reset TAC to the admin/user account's email, scoped to the given tenant.
    void sendResetTac(String tenantCode, String email);

    // Verifies the TAC (one-time use) and updates the admin/user's password.
    void resetPassword(String tenantCode, String email, String tac, String newPassword);
}
