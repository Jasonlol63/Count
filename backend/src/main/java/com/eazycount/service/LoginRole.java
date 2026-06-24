package com.eazycount.service;

/**
 * Login tab selected on the SPA login page ({@code login_role} form field).
 */
public enum LoginRole {
    ADMIN,
    MEMBER;

    public static LoginRole fromValue(String value) {
        if (value != null && "member".equalsIgnoreCase(value.trim())) {
            return MEMBER;
        }
        return ADMIN;
    }
}
