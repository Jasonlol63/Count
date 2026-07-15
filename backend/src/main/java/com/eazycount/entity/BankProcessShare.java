package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Maps to {@code bank_process_share} — profit sharing lines for a {@link BankProcess}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BankProcessShare {

    private Integer id;

    private Integer bankProcessId;

    private Integer accountId;

    private BigDecimal amount;

    private Integer sortOrder;
}
