package com.eazycount.dto;

import com.eazycount.entity.Tenant;
import com.eazycount.entity.User;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserTenantDTO {
    private User user;
    private Tenant tenant;
}
