package com.eazycount.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory lookup over {@link ItOperatorProperties} (loaded from it-operators.yml at
 * startup). Deliberately not a DB table — see the design discussion this backs: IT
 * accounts must stay physically separate from the admin/user tables.
 */
@Component
public class ItOperatorRegistry {

    private final Map<String, ItOperatorProperties.Operator> byUsername = new HashMap<>();

    @Autowired
    public ItOperatorRegistry(ItOperatorProperties properties) {
        for (ItOperatorProperties.Operator op : properties.getOperators()) {
            if (op.getUsername() == null || op.getUsername().isBlank()) {
                continue;
            }
            byUsername.put(normalize(op.getUsername()), op);
        }
    }

    /** {@code normalizedUsername} must already be upper-cased/trimmed (same normalize() as AuthServiceImpl). */
    public ItOperatorProperties.Operator findByUsername(String normalizedUsername) {
        if (normalizedUsername == null || normalizedUsername.isBlank()) {
            return null;
        }
        return byUsername.get(normalize(normalizedUsername));
    }

    public boolean verifyPassword(ItOperatorProperties.Operator operator, String rawPassword, PasswordEncoder passwordEncoder) {
        return operator != null
                && operator.getPasswordHash() != null
                && passwordEncoder.matches(Objects.toString(rawPassword, ""), operator.getPasswordHash());
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase();
    }
}
