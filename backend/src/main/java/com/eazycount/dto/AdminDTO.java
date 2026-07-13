package com.eazycount.dto;

import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminDTO {

    private Admin admin;

    private AdminTenantAccess adminTenantAccess;

    private AdminTenantProcessAccess adminTenantProcessAccess;

    private AdminTenantAccountAccess adminTenantAccountAccess;

    /** Create / update request — flat JSON from frontend. */
    private Integer id;
    private Integer scopeTenantId;
    private Long tenantAccessId;
    private String loginId;
    private String name;
    private String email;
    private String password;
    private String secondaryPassword;
    private String role;
    private String status;
    private Boolean readOnly;
    private List<Integer> tenantIds;
    private List<String> permissions;
    private List<AccountPermissionItem> accountPermissions;
    private List<ProcessPermissionItem> processPermissions;

    /** Synthetic owner row in admin list (from {@code owner} table, not {@code user}). */
    private Boolean isOwnerShadow;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountPermissionItem {
        @JsonProperty("id")
        private Integer accountId;

        @JsonProperty("account_id")
        private String accountCode;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProcessPermissionItem {
        @JsonProperty("id")
        private Integer processId;

        @JsonProperty("process_id")
        private String processCode;

        private String description;
    }

}
