package com.eazycount.dto;

import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Tenant;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Result of a successful login: identity + tenants resolved in the service layer.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultDTO {
    private UserDTO identity;
    /** Tenant the user typed in the login form ({@code tenant_code} field). */
    private Tenant loginTenant;
    /** Tenant the user is allowed to enter after access check. */
    private Tenant sessionTenant;
    /** Active feature modules for {@link #sessionTenant} (from tenant_feature_module). */
    private List<FeatureModule> sessionFeatureModules;
    /** API user_type: member | user | owner */
    private String userType;
    /** Post-login redirect path for the SPA. */
    private String redirect;
}
