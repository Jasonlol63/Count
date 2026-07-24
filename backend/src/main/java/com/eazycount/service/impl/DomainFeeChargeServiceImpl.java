package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.DomainListFeePriceDao;
import com.eazycount.dao.TenantFeeShareAllocateDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.UserDao;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.TenantFeeShareAllocate;
import com.eazycount.entity.Transaction;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DomainFeeChargeService;
import com.eazycount.util.TransactionMoneyFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class DomainFeeChargeServiceImpl implements DomainFeeChargeService {

    /** Prefer the "C168" account code; fall back to a "PROFIT" code if C168 itself was renamed. */
    private static final String[] PROFIT_ACCOUNT_CODES = {"C168", "PROFIT"};

    private static final String LEDGER_CURRENCY_CODE = "MYR";

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private DomainListFeePriceDao domainListFeePriceDao;

    @Autowired
    private TenantFeeShareAllocateDao feeShareAllocateDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private TransactionDao transactionDao;

    @Override
    @Transactional
    public int chargeDomainFeeIfRequested(Tenant tenant) {
        if (tenant == null || !Boolean.TRUE.equals(tenant.getChargeDomainFeeOnConfirm())) {
            return 0;
        }
        return chargeDomainFee(tenant, tenant.getDomainFeePeriod());
    }

    @Override
    @Transactional
    public int chargeDomainFee(Tenant tenant, String periodCode) {
        if (tenant == null) {
            throw new BusinessException("Invalid tenant for domain fee charge");
        }

        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        Integer payerTenantId = tenant.getId();
        String payerCode = tenant.getCode() != null ? tenant.getCode().trim().toUpperCase() : "";
        if (payerTenantId == null || payerTenantId <= 0 || payerCode.isEmpty()) {
            throw new BusinessException("Invalid tenant for domain fee charge");
        }

        String period = periodCode != null ? periodCode.trim() : "";
        if (period.isEmpty()) {
            throw new BusinessException("Domain fee period is required to charge the domain fee");
        }

        Tenant.TenantType tenantType = tenant.getTenantType() != null
                ? tenant.getTenantType()
                : Tenant.TenantType.COMPANY;

        BigDecimal domainFeeAmount = domainListFeePriceDao.findPriceByTenantTypeAndPeriod(tenantType, period);
        if (domainFeeAmount == null || domainFeeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Domain fee price is not configured for period: " + period);
        }
        domainFeeAmount = scaleMoney(domainFeeAmount);

        Tenant c168Tenant = domainDao.findTenantByCodeAndOwnerId("C168", 1);
        if (c168Tenant == null || c168Tenant.getId() == null) {
            throw new BusinessException("C168 ledger tenant not found");
        }
        Integer c168TenantId = c168Tenant.getId();

        Currency ledgerCurrency = currencyDao.findByTenantIdAndCode(c168TenantId, LEDGER_CURRENCY_CODE);
        if (ledgerCurrency == null || ledgerCurrency.getId() == null) {
            throw new BusinessException("MYR currency is not configured for C168");
        }
        Integer currencyId = ledgerCurrency.getId();

        Integer payerAccountId = userDao.findAccountIdByTenantIdAndCode(c168TenantId, payerCode);
        if (payerAccountId == null) {
            throw new BusinessException("Payer account not found in C168 for: " + payerCode);
        }

        Integer profitAccountId = resolveProfitAccountId(c168TenantId);
        if (profitAccountId == null) {
            throw new BusinessException("C168 profit account not found");
        }

        List<TenantFeeShareAllocate> allocations = feeShareAllocateDao.findFeeShareByTenantId(payerTenantId);
        if (allocations == null) {
            allocations = List.of();
        }

        boolean hasProfitAllocation = allocations.stream()
                .anyMatch(row -> row != null
                        && row.getShareType() == TenantFeeShareAllocate.ShareType.PROFIT
                        && row.getAccountId() != null && row.getAccountId() > 0);
        if (!hasProfitAllocation) {
            throw new BusinessException("Profit share (C168) is required before charging the domain fee");
        }

        LocalDate transactionDate = LocalDate.now();
        LocalDateTime approvedAt = LocalDateTime.now();
        String createdBy = session.login_id;

        List<Transaction> lines = new ArrayList<>();

        lines.add(buildPaymentLine(c168TenantId, payerAccountId, profitAccountId, currencyId,
                domainFeeAmount, transactionDate, "PAY DOMAIN FEE", createdBy, approvedAt));

        BigDecimal commissionTotal = BigDecimal.ZERO;
        for (TenantFeeShareAllocate row : allocations) {
            if (row == null || row.getShareType() == TenantFeeShareAllocate.ShareType.PROFIT) {
                continue;
            }
            if (row.getAccountId() == null || row.getAccountId() <= 0) {
                continue;
            }
            BigDecimal percentage = row.getPercentage() != null ? row.getPercentage() : BigDecimal.ZERO;
            if (percentage.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal amount = scaleMoney(
                    domainFeeAmount.multiply(percentage)
                            .divide(new BigDecimal("100"),
                                    TransactionMoneyFormat.NORMAL_AMOUNT_SCALE,
                                    RoundingMode.HALF_UP));
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            commissionTotal = commissionTotal.add(amount);
            lines.add(buildPaymentLine(c168TenantId, profitAccountId, row.getAccountId(), currencyId,
                    amount, transactionDate,
                    row.getShareType().name() + " COMMISSION FROM " + payerCode,
                    createdBy, approvedAt));
        }

        BigDecimal profitAmount = scaleMoney(domainFeeAmount.subtract(commissionTotal));
        if (profitAmount.compareTo(BigDecimal.ZERO) < 0) {
            profitAmount = BigDecimal.ZERO;
        }
        if (profitAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(buildPaymentLine(c168TenantId, profitAccountId, profitAccountId, currencyId,
                    profitAmount, transactionDate, "NET PROFIT FROM " + payerCode, createdBy, approvedAt));
        }

        for (Transaction line : lines) {
            transactionDao.insert(line);
        }
        return lines.size();
    }

    /* C168's own account under its ledger — the fixed Profit recipient regardless of allocation account_id. */
    private Integer resolveProfitAccountId(Integer c168TenantId) {
        for (String code : PROFIT_ACCOUNT_CODES) {
            Integer id = userDao.findAccountIdByTenantIdAndCode(c168TenantId, code);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /* Build Payment Details Use */
    private Transaction buildPaymentLine(Integer tenantId, Integer toAccountId, Integer fromAccountId,
            Integer currencyId, BigDecimal amount, LocalDate transactionDate, String description,
            String createdBy, LocalDateTime approvedAt) {
        Transaction txn = new Transaction();
        txn.setTenantId(tenantId);
        txn.setTransactionType(Transaction.TransactionType.PAYMENT);
        txn.setAccountId(toAccountId);
        txn.setFromAccountId(fromAccountId);
        txn.setCurrencyId(currencyId);
        txn.setAmount(amount);
        txn.setTransactionDate(transactionDate);
        txn.setDescription(description);
        txn.setRemark(null);
        txn.setCreatedBy(createdBy);
        txn.setUpdatedBy(null);
        txn.setApprovalStatus(Transaction.ApprovalStatus.APPROVED);
        txn.setApprovedBy(createdBy);
        txn.setApprovedAt(approvedAt);
        txn.setBankProcessPostedId(null);
        return txn;
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return TransactionMoneyFormat.normalizeComputedNormal(value);
    }
}
