package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.LoginResultDTO;
import com.eazycount.jwt.JwtService;
import com.eazycount.security.*;
import com.eazycount.service.AuthService;
import com.eazycount.service.LoginRole;
import com.eazycount.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final PermissionService permissionService;
    private final JwtService jwtService;
    private final AuthTokenStore authTokenStore;

    public AuthController(
            AuthService authService,
            PermissionService permissionService,
            JwtService jwtService,
            AuthTokenStore authTokenStore
    ) {
        this.authService = authService;
        this.permissionService = permissionService;
        this.jwtService = jwtService;
        this.authTokenStore = authTokenStore;
    }

    @PostMapping("/login")
    public Map<String, Object> login(
            @RequestParam("tenant_code") String tenantCodeInput,
            @RequestParam String password,
            @RequestParam(name = "login_role", defaultValue = "admin") String loginRole,
            @RequestParam(name = "login_id", required = false) String loginId,
            @RequestParam(name = "account_id", required = false) String accountId,
            HttpServletResponse response
    ) {
        LoginRole role = LoginRole.fromValue(loginRole);
        String username = role == LoginRole.MEMBER ? accountId : loginId;

        if (username == null || username.isBlank()) {
            throw new BusinessException(role == LoginRole.MEMBER
                    ? "Please enter account ID"
                    : "Please enter username");
        }

        LoginResultDTO result = authService.login(tenantCodeInput, username, password, role);
        SessionUser sessionUser = authService.applyInitialSecondaryState(
                SessionUser.from(
                        result.getIdentity(),
                        result.getSessionTenant(),
                        permissionService
                ),
                result
        );

        String subject = sessionUser.login_id != null && !sessionUser.login_id.isBlank()
                ? sessionUser.login_id
                : String.valueOf(sessionUser.user_id);
        JwtService.IssuedToken issued = jwtService.createAccessToken(subject);
        authTokenStore.save(issued.jti(), sessionUser, issued.ttlMillis());
        AuthCookieHelper.setAccessTokenCookie(response, jwtService, issued.token());

        return authService.toLoginResponse(result);
    }

    /**
     * SPA bootstrap — replaces {@code api/session/current_user_api.php}.
     */
    @GetMapping("/current-user")
    public ResponseEntity<Map<String, Object>> currentUser() {
        SessionUser user = SecurityUtils.currentUser();
        if (user == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "Not logged in");
            body.put("data", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "");
        body.put("data", user);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/verify-owner-secondary-password")
    public Map<String, Object> verifyOwnerSecondaryPassword(
            @RequestParam("secondary_password") String secondaryPassword
    ) {
        LoginUserPrincipal principal = SecurityUtils.currentPrincipal()
                .orElseThrow(() -> new BusinessException("Unauthorized"));
        authService.verifyOwnerSecondaryPassword(
                secondaryPassword,
                principal.user(),
                principal.jti(),
                jwtService.getAccessTokenExpiration()
        );
        return Map.of("success", true, "message", "Verified");
    }

    @PostMapping("/verify-user-secondary-password")
    public Map<String, Object> verifyUserSecondaryPassword(
            @RequestParam("secondary_password") String secondaryPassword
    ){
        LoginUserPrincipal principal = SecurityUtils.currentPrincipal()
                .orElseThrow(() -> new BusinessException("Unauthorized"));
        authService.verifyUserSecondaryPassword(
                secondaryPassword,
                principal.user(),
                principal.jti(),
                jwtService.getAccessTokenExpiration()
        );
        return Map.of("success", true, "message", "Verified");
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        return authService.logout(request, response);
    }
}
