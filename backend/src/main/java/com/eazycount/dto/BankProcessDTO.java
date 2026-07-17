package com.eazycount.dto;

import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BankProcessShare;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Bank Process list row (entity + join labels) and flat write fields for add.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BankProcessDTO {

    private Integer id;

    private BankProcess bankProcess;

    private String countryCode;

    private String bankName;

    private String supplierAccountCode;

    private String supplierAccountName;

    private String customerAccountCode;

    private String customerAccountName;

    private String companyAccountCode;

    private String companyAccountName;

    private String status;

    private Integer tenantId;

    private Integer countryId;

    private Integer bankOptionId;

    private String cardOwner;

    private String cardOwnerType;

    private LocalDate dayStart;

    private LocalDate dayEnd;

    private Boolean dayEndMonthlyCapEnabled;

    private String frequency;

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

    private List<BankProcessShare> shares;
}
