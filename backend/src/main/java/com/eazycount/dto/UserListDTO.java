package com.eazycount.dto;

import com.eazycount.entity.User;
import com.eazycount.entity.UserCurrency;
import com.eazycount.entity.UserTenantAccess;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserListDTO {
    private User user;
    private UserTenantAccess userTenantAccess;

    private Integer id;              // user.id
    private Long tenantAccessId;     // user_tenant_access.id（从 list 的 tenantAccess.id 来）
    private Integer scopeTenantId;

    private String accountId;
    private String name;
    private String role;
    private String password;
    private User.AccountStatus status;
    private String createdSource;
    private Integer paymentAlert;
    private String alertDay;
    private BigDecimal alertAmount;
    private Date alertSpecificDate;
    private String remark;
    private LocalDateTime lastLogin;

    /** tenant.id list (company.id in frontend picker) */
    private List<Integer> tenantIds;
}
