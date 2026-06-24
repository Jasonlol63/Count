package com.eazycount.security;

import com.eazycount.jwt.JwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

public final class AuthCookieHelper {

    private AuthCookieHelper() {
    }

    public static void setAccessTokenCookie(HttpServletResponse response, JwtService jwtService, String token) {
        final Cookie cookie = new Cookie(jwtService.getCookieName(), token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) (jwtService.getAccessTokenExpiration() / 1000L));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public static void clearAccessTokenCookie(HttpServletResponse response, JwtService jwtService) {
        final Cookie cookie = new Cookie(jwtService.getCookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
