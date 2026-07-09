package com.eazycount.util;

public final class SecondaryPasswordUtils {

    private SecondaryPasswordUtils() {
    }

    public static boolean isConfigured(String secondaryPassword) {
        return secondaryPassword != null && !secondaryPassword.isBlank();
    }
}
