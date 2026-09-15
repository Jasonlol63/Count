package com.eazycount.security;

/**
 * Resolved IT-operator identity carried through the login flow (UserDTO → SessionUser),
 * kept separate from Admin/Owner/User since it doesn't come from the database.
 */
public class ItOperatorIdentity {

    private final String username;
    private final String displayName;

    public ItOperatorIdentity(String username, String displayName) {
        this.username = username;
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }
}
