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

    /* Bank Balance one-off Contra settlement. On add/update requests: the amount to create a new
     * Contra for (ignored by the service once a linked one already exists — see
     * bankBalanceTransactionId). On list rows: the linked Contra's amount, or null if none. */
    private BigDecimal bankBalance;

    /* List rows only: id of the linked Contra transaction (transactions.bank_process_id), or null
     * if this process has no Bank Balance settlement yet. Drives the locked/unlocked field state
     * on the frontend. Not meaningful on add/update requests. */
    private Integer bankBalanceTransactionId;

    private List<BankProcessShare> shares;
}
