package com.eazycount.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public final class LoginUserPrincipal implements UserDetails {

    private final SessionUser user;
    private final String jti;

    public LoginUserPrincipal(SessionUser user, String jti) {
        this.user = user;
        this.jti = jti;
    }

    public SessionUser user() {
        return user;
    }

    public String jti() {
        return jti;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (user.role == null || user.role.isBlank()) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.role.toUpperCase()));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return user.login_id != null ? user.login_id : String.valueOf(user.user_id);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
