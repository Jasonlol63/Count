package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One Accounting Due inbox row for {@code FIRST_OF_EVERY_MONTH} generation.
 * Computed on demand (not persisted) from a {@code bank_process} row + today.
 *
 * <p>Period rules (1st-of-every-month):
 * <ul>
 *   <li>First month, dayStart on the 1st → {@code FIRST_MONTH}, [dayStart, monthEnd]</li>
 *   <li>First month, dayStart mid-month → {@code PARTIAL_FIRST_MONTH}, [dayStart, monthEnd]</li>
 *   <li>Middle / full end month → {@code FULL_MONTH}, [1st, monthEnd]</li>
 *   <li>End month, dayEnd before month end → {@code DAY_END_TAIL}, [1st, dayEnd]</li>
 * </ul>
 * {@code postedDate} = dayStart for the first month, else the 1st of the current month.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountingDueDTO {

    private Integer bankProcessId;

    private Integer tenantId;

    /** Due anchor / display date. First month = dayStart; otherwise 1st of current month. */
    private LocalDate postedDate;

    private String periodType;

    private LocalDate billingStart;

    private LocalDate billingEnd;

    private String countryCode;

    private String bankName;

    private String cardOwner;

    private String cardOwnerType;

    private String frequency;

    private LocalDate dayStart;

    private LocalDate dayEnd;

    private String contract;

    private String status;

    private String supplierAccountCode;

    private String supplierAccountName;

    private BigDecimal supplierPrice;

    private String customerAccountCode;

    private String customerAccountName;

    private BigDecimal customerPrice;

    private String companyAccountCode;

    private String companyAccountName;

    private BigDecimal companyPrice;
}
