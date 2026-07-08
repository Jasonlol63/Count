package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to {@code permission} — sidebar module dictionary.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    private Integer id;

    /** Machine code e.g. HOME, DOMAIN, REPORT */
    private String code;

    private String name;

    private Integer sortOrder;

    /**
     * FK {@code feature_module.id}; when set, permission is effective only if tenant has that feature.
     */
    private Integer requiresFeatureId;

    private String status;
}
