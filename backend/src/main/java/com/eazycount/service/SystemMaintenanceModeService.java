package com.eazycount.service;

public interface SystemMaintenanceModeService {

    /**
     * No permission check by design — called both by {@code JwtAuthTokenFilter} (auth
     * infrastructure, runs before any session/role is established) and by the IT console's
     * status endpoint (which enforces IT-only access itself before calling this).
     */
    boolean isEnabled();

    /** IT-only; throws if the current session isn't role=="it". Flips the global switch. */
    void setEnabled(boolean enabled);
}
