package com.eazycount.dto;

import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.TenantFeatureModule;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenantDTO {

    private Tenant tenant;
    private FeatureModule featureModule;
    private TenantFeatureModule tenantFeatureModule;
}
