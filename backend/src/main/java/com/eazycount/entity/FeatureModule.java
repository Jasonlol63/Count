package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to {@code feature_module} — canonical business module dictionary.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class FeatureModule {

    private Integer id;

    /** Canonical code e.g. Gambling, Bank (legacy JSON used same strings) */
    private String code;

    private String name;

    private Integer sortOrder;

    private String status;
}
