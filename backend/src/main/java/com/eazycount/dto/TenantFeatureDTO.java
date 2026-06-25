package com.eazycount.dto;

import com.eazycount.entity.FeatureModule;
import com.eazycount.entity.Tenant;
import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TenantFeatureDTO {
    private Tenant tenant;
    private FeatureModule featureModule;
}
