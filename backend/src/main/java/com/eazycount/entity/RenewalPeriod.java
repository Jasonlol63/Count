package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to {@code renewal_period} — period dictionary for domain fees and auto-renew.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RenewalPeriod {

    private String code;

    private Integer sortOrder;

    private String label;
}
