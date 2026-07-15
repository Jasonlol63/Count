package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code bank_option} — banks under a {@link BankCountry}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BankOption {

    private Integer id;

    private Integer tenantId;

    /** FK {@code bank_country.id} */
    private Integer countryId;

    /** Bank name e.g. UBANK, RHB, CIMB */
    private String name;

    private LocalDateTime createdAt;
}
