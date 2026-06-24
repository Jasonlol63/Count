package com.eazycount.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<LoginUserPrincipal> currentPrincipal() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static SessionUser currentUser() {
        return currentPrincipal().map(LoginUserPrincipal::user).orElse(null);
    }
}
