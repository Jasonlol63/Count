package com.eazycount.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code it-operators.yml} (kept outside the repo/jar — see that file's own
 * comment). Same binding style as {@link com.eazycount.jwt.JwtService}.
 */
@Component
@ConfigurationProperties(prefix = "it-operators")
public class ItOperatorProperties {

    private List<Operator> operators = new ArrayList<>();

    public List<Operator> getOperators() {
        return operators;
    }

    public void setOperators(List<Operator> operators) {
        this.operators = operators;
    }

    public static class Operator {
        private String username;
        private String passwordHash;
        private String displayName;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}
