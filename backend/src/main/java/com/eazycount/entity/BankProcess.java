package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code bank_process} — Bank Process deal row (list + add/update).
 * Waiting display can be derived from {@code dayStart} when status is ongoing.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BankProcess {

    private Integer id;

    private Integer tenantId;

    private Integer countryId;

    private Integer bankOptionId;

    private String cardOwner;

    private String cardOwnerType;

    private LocalDate dayStart;

    private LocalDate dayEnd;

    private Boolean dayEndMonthlyCapEnabled;

    private Frequency frequency;

    private Integer supplierAccountId;

    private BigDecimal supplierPrice;

    private Integer customerAccountId;

    private BigDecimal customerPrice;

    private Integer companyAccountId;

    private BigDecimal companyPrice;

    private String contract;

    private BigDecimal insurancePrice;

    private String sop;

    private String remark;

    private Status status;

    private LocalDate resendScheduleDayStart;

    private LocalDate resendScheduleDayEnd;

    private Frequency resendScheduleFrequency;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum Frequency {
        FIRST_OF_EVERY_MONTH,
        MONTHLY,
        ONCE,
        DAY,
        WEEK
    }

    @Getter
    public enum Status {
        WAITING,
        ACTIVE,
        OFFICIAL,
        E_INVOICE,
        INACTIVE,
        BLOCK
    }
}
