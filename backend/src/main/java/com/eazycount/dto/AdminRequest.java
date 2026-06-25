package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AdminRequest {
    private Integer id;              // user.id
    private Long tenantAccessId;     // user_tenant_access.id（从 list 的 tenantAccess.id 来）
    private Integer scopeTenantId;

    private String loginId;
    private String name;
    private String email;
    private String password;
    private String secondaryPassword;
    private String role;
    private String status;
    private Boolean readOnly;
    /** JSON array string or List — sidebar permissions */
    private Object permissions;
    /** tenant.id list (company.id in frontend picker) */
    private List<Integer> tenantIds;
    private Object accountPermissions;
    private Object processPermissions;
}
