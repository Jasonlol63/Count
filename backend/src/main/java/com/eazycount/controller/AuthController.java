package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.service.AuthService;
import com.eazycount.service.LoginRole;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(
            @RequestParam("company_id") String tenantCodeInput,
            @RequestParam String password,
            @RequestParam(name = "login_role", defaultValue = "admin") String loginRole,
            @RequestParam(name = "login_id", required = false) String loginId,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        LoginRole role = LoginRole.fromValue(loginRole);
        String username = role == LoginRole.MEMBER ? accountId : loginId;

        if (username == null || username.isBlank()) {
            throw new BusinessException(role == LoginRole.MEMBER
                    ? "Please enter account ID"
                    : "Please enter username");
        }

        return authService.toLoginResponse(
                authService.login(tenantCodeInput, username, password, role)
        );
    }
}
