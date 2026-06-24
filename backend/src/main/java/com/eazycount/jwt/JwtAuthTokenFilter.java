package com.eazycount.jwt;

import com.eazycount.security.AuthTokenStore;
import com.eazycount.security.LoginUserPrincipal;
import com.eazycount.security.SessionUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class JwtAuthTokenFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthTokenStore authTokenStore;

    public JwtAuthTokenFilter(JwtService jwtService, AuthTokenStore authTokenStore) {
        this.jwtService = jwtService;
        this.authTokenStore = authTokenStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        resolveToken(request).ifPresent(token -> authenticate(token, request));
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            final Claims claims = jwtService.parseToken(token);
            final String jti = claims.getId();
            if (!StringUtils.hasText(jti)) {
                return;
            }

            final Optional<SessionUser> user = authTokenStore.find(jti);
            if (user.isEmpty()) {
                return;
            }

            final LoginUserPrincipal principal = new LoginUserPrincipal(user.get(), jti);
            final UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(SessionUser.SESSION_KEY, user.get());
        } catch (Exception ignored) {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<String> resolveToken(HttpServletRequest request) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7).trim());
        }

        final Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        final String cookieName = jwtService.getCookieName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue().trim());
            }
        }
        return Optional.empty();
    }
}
