package com.eazycount.dto;

import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OwnerTenantDTO {
    private Owner owner;
    private Tenant tenant;
}
