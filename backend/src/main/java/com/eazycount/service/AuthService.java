package com.eazycount.service;

import com.eazycount.dto.AdminTenantDTO;
import com.eazycount.dto.LoginResultDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.UserTenantDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import com.eazycount.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

public interface AuthService {
    List<OwnerTenantDTO> findAccessibleTenantsByOwnerId(Integer ownerId, String tenantCode);

    List<AdminTenantDTO> findAccessibleTenantsByAdminId(Integer adminId, String tenantCode);

    List<UserTenantDTO> findAccessibleTenantsByMemberId(Integer userId, String tenantCode);

    Admin findAdminByLoginId(String loginId);

    Owner findOwnerByOwnerCode(String ownerCode);

    User findMemberByAccountId(String accountId);

    List<Tenant> findActiveTenantsByLoginCode(String tenantCode);

    LoginResultDTO login(String tenantCode, String username, String password, LoginRole role);

    Map<String, Object> toLoginResponse(LoginResultDTO result);

    SessionUser applyInitialSecondaryState(SessionUser sessionUser, LoginResultDTO result);

    void verifyOwnerSecondaryPassword(String secondaryPassword, SessionUser current, String jti, long ttlMillis);

    void verifyUserSecondaryPassword(String secondaryPassword, SessionUser current, String jti, long ttlMillis);

    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response);
}
