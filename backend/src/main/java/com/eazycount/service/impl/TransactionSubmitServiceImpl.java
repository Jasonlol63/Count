package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.TransactionRateDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Transaction;
import com.eazycount.entity.TransactionRate;
import com.eazycount.entity.User;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionSubmitService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TransactionSubmitServiceImpl implements TransactionSubmitService {

    static final String ADJUSTMENT_DESCRIPTION = "ADJUSTMENT - WIN/LOSS";
    /** Stored on RATE Middle-Man multiplier leg; History rewrites to MARKUP {rate}…. */
    static final String RATE_MIDDLEMAN_RATE_DESCRIPTION = "RATE_MIDDLEMAN_RATE";
    /** Stored on RATE Middle-Man fee leg; History rewrites to MARKUP X…. */
    static final String RATE_MIDDLEMAN_FEE_DESCRIPTION = "RATE_MIDDLEMAN_FEE";

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 8;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    /** Max |leg2 − leg1×rate| allowed after 2dp rounding. */
    private static final BigDecimal RATE_AMOUNT_TOLERANCE = new BigDecimal("0.02");

    private static final Set<String> TRANSFER_TYPES = Set.of(
            Transaction.TransactionType.PAYMENT.name(),
            Transaction.TransactionType.CLAIM.name(),
            Transaction.TransactionType.CLEAR.name(),
            Transaction.TransactionType.CONTRA.name());

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private TransactionRateDao transactionRateDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    @Transactional
    public TransactionDTO.SubmitResult submit(TransactionDTO.SubmitRequest request) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (session.read_only == 1) {
            throw new BusinessException("Read-only access cannot submit transactions");
        }
        if (request == null) {
            throw new BusinessException("Invalid request");
        }

        Integer tenantId = request.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        String typeRaw = request.getTransactionType() != null
                ? request.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : Transaction.TransactionType.PAYMENT.name();

        if (Transaction.TransactionType.ADJUSTMENT.name().equals(typeRaw)) {
            return submitAdjustment(request, session, tenantId);
        }
        if (Transaction.TransactionType.PROFIT.name().equals(typeRaw)) {
            return submitProfit(request, session, tenantId);
        }
        if (Transaction.TransactionType.RATE.name().equals(typeRaw)) {
            return submitRate(request, session, tenantId);
        }
        if (TRANSFER_TYPES.contains(typeRaw)) {
            return submitTransfer(request, session, tenantId, Transaction.TransactionType.valueOf(typeRaw));
        }
        throw new BusinessException("Unsupported transaction type: " + typeRaw);
    }

    private TransactionDTO.SubmitResult submitTransfer(
            TransactionDTO.SubmitRequest request,
            SessionUser session,
            Integer tenantId,
            Transaction.TransactionType transactionType) {
        FromToAccounts accounts = requireFromToAccounts(
                request.getToAccountId(), request.getFromAccountId(),
                request.getCurrencyId(), request.getCurrencyCode(),
                tenantId, "To account", "From account");
        BigDecimal amount = parsePositiveAmount(request.getAmount(), "Amount");
        return insertAndBuildResult(
                session, tenantId, transactionType, accounts.toAccountId(), accounts.fromAccountId(),
                accounts.currency(), amount, resolveTransactionDate(request),
                trimToNull(request.getRemark()), null, null);
    }

    private TransactionDTO.SubmitResult submitProfit(
            TransactionDTO.SubmitRequest request,
            SessionUser session,
            Integer tenantId) {
        FromToAccounts accounts = requireFromToAccounts(
                request.getToAccountId(), request.getFromAccountId(),
                request.getCurrencyId(), request.getCurrencyCode(),
                tenantId, "To account", "From account");
        BigDecimal amount = parsePositiveAmount(request.getAmount(), "Amount");
        return insertAndBuildResult(
                session, tenantId, Transaction.TransactionType.PROFIT,
                accounts.toAccountId(), accounts.fromAccountId(), accounts.currency(),
                amount, resolveTransactionDate(request), trimToNull(request.getRemark()), null, null);
    }

    private TransactionDTO.SubmitResult submitAdjustment(
            TransactionDTO.SubmitRequest request,
            SessionUser session,
            Integer tenantId) {
        Integer toAccountId = request.getToAccountId();
        if (toAccountId == null || toAccountId <= 0) {
            throw new BusinessException("To account is required");
        }
        if (request.getFromAccountId() != null && request.getFromAccountId() > 0) {
            throw new BusinessException("From account is not used for ADJUSTMENT");
        }

        UserListDTO toAccount = requireActiveAccount(toAccountId, tenantId, "To account");
        Currency currency = resolveCurrency(tenantId, request.getCurrencyId(), request.getCurrencyCode());
        requireAccountCurrency(tenantId, toAccountId, currency.getId(), toAccount.getAccountId());

        BigDecimal amount = parseSignedNonZeroAmount(request.getAmount());
        return insertAndBuildResult(
                session, tenantId, Transaction.TransactionType.ADJUSTMENT, toAccountId, null, currency,
                amount, resolveTransactionDate(request), trimToNull(request.getRemark()),
                ADJUSTMENT_DESCRIPTION, null);
    }

    /*
     * RATE: two Cr/Dr legs + optional Middle-Man Win/Loss legs (second currency) + transactions_rate.
     * Middle-Man: account required with fee and/or rate multiplier (either or both).
     * Rate portion = leg1Amount × middlemanRate (From=middleman +, To=leg2 payer −).
     * Fee portion = feeInput(first ccy) × exchangeRate — middleman-only +Win/Loss (no counterparty).
     * leg2 net = gross − (ratePortion + feePortion).
     */
    private TransactionDTO.SubmitResult submitRate(TransactionDTO.SubmitRequest request, SessionUser session, Integer tenantId) {
        FromToAccounts leg1 = requireFromToAccounts(
                request.getLeg1ToAccountId(), request.getLeg1FromAccountId(),
                request.getLeg1CurrencyId(), request.getLeg1CurrencyCode(),
                tenantId, "Leg1 To account", "Leg1 From account");
        FromToAccounts leg2 = requireFromToAccounts(
                request.getLeg2ToAccountId(), request.getLeg2FromAccountId(),
                request.getLeg2CurrencyId(), request.getLeg2CurrencyCode(),
                tenantId, "Leg2 To account", "Leg2 From account");

        if (leg1.currency().getId().equals(leg2.currency().getId())) {
            throw new BusinessException("RATE leg1 and leg2 currencies must be different");
        }

        BigDecimal amountFrom = parsePositiveAmount(request.getLeg1Amount(), "Leg1 amount");
        BigDecimal amountTo = parsePositiveAmount(request.getLeg2Amount(), "Leg2 amount");
        BigDecimal exchangeRate = parsePositiveExchangeRate(request.getExchangeRate());
        BigDecimal grossTo = amountFrom.multiply(exchangeRate).setScale(MONEY_SCALE, MONEY_ROUNDING);

        MiddlemanSpec middleman = resolveMiddleman(request, tenantId, leg2, amountFrom, exchangeRate, grossTo);
        if (middleman == null) {
            validateRateAmounts(amountFrom, amountTo, exchangeRate);
        } else {
            BigDecimal expectedNet = grossTo.subtract(middleman.totalLeg2()).setScale(MONEY_SCALE, MONEY_ROUNDING);
            BigDecimal delta = expectedNet.subtract(amountTo).abs();
            if (delta.compareTo(RATE_AMOUNT_TOLERANCE) > 0) {
                throw new BusinessException(
                        "Leg2 amount must equal (leg1 × exchange rate) − middleman total (expected "
                                + TransactionMoneyFormat.formatMoney(expectedNet) + ")");
            }
        }

        String rateExpression = trimToNull(request.getRateExpression());
        String remark = trimToNull(request.getRemark());
        LocalDate transactionDate = resolveTransactionDate(request);
        String rateGroupId = newRateGroupId();

        String leg1Ccy = leg1.currency().getCode() != null
                ? leg1.currency().getCode().trim().toUpperCase(Locale.ROOT)
                : "";
        // When Fee is used: leg1 (toAccount1) History remark = CHARGE {ccy1} {feeInput} SERVICE FEES
        String serviceFeeRemark = null;
        if (middleman != null && middleman.feeInput() != null) {
            serviceFeeRemark = formatServiceFeeRemark(leg1Ccy, middleman.feeInput());
        }
        String leg1Remark = serviceFeeRemark != null ? serviceFeeRemark : remark;

        Transaction leg1Txn = insertApproved(
                session, tenantId, Transaction.TransactionType.RATE,
                leg1.toAccountId(), leg1.fromAccountId(), leg1.currency().getId(),
                amountFrom, transactionDate, leg1Remark, null, rateGroupId);
        Transaction leg2Txn = insertApproved(
                session, tenantId, Transaction.TransactionType.RATE,
                leg2.toAccountId(), leg2.fromAccountId(), leg2.currency().getId(),
                amountTo, transactionDate, remark, null, rateGroupId);

        Integer middlemanRateTxnId = null;
        Integer middlemanFeeTxnId = null;
        if (middleman != null) {
            // From=middleman (+WL), To=leg2 payer (−WL) — same signs as PROFIT; second currency.
            if (middleman.ratePortion() != null) {
                Transaction rateTxn = insertApproved(
                        session, tenantId, Transaction.TransactionType.RATE,
                        leg2.toAccountId(), middleman.accountId(), leg2.currency().getId(),
                        middleman.ratePortion(), transactionDate, remark,
                        RATE_MIDDLEMAN_RATE_DESCRIPTION, rateGroupId);
                middlemanRateTxnId = rateTxn.getId();
            }
            if (middleman.feePortion() != null) {
                // Fee: middleman-only +Win/Loss (no −WL on leg2 payer — fee already in leg1 amount).
                Transaction feeTxn = insertApproved(
                        session, tenantId, Transaction.TransactionType.RATE,
                        middleman.accountId(), null, leg2.currency().getId(),
                        middleman.feePortion(), transactionDate, remark,
                        RATE_MIDDLEMAN_FEE_DESCRIPTION, rateGroupId);
                middlemanFeeTxnId = feeTxn.getId();
            }
        }

        TransactionRate header = new TransactionRate();
        header.setTenantId(tenantId);
        header.setRateGroupId(rateGroupId);
        header.setLeg1TransactionId(leg1Txn.getId());
        header.setLeg2TransactionId(leg2Txn.getId());
        header.setExchangeRate(exchangeRate);
        header.setRateExpression(rateExpression);
        header.setCurrencyFromId(leg1.currency().getId());
        header.setAmountFrom(amountFrom);
        header.setCurrencyToId(leg2.currency().getId());
        header.setAmountTo(amountTo);
        if (middleman != null) {
            header.setMiddlemanAccountId(middleman.accountId());
            header.setMiddlemanRate(middleman.rate());
            // Store fee input in first currency (not converted).
            header.setMiddlemanAmount(middleman.feeInput());
        } else {
            header.setMiddlemanAccountId(null);
            header.setMiddlemanRate(null);
            header.setMiddlemanAmount(null);
        }
        transactionRateDao.insert(header);

        TransactionDTO.SubmitResult result = new TransactionDTO.SubmitResult();
        result.setId(leg1Txn.getId());
        result.setTransactionType(Transaction.TransactionType.RATE.name());
        result.setTenantId(tenantId);
        result.setToAccountId(leg1.toAccountId());
        result.setFromAccountId(leg1.fromAccountId());
        result.setCurrencyCode(leg1.currency().getCode() != null
                ? leg1.currency().getCode().trim().toUpperCase(Locale.ROOT)
                : "");
        result.setAmount(TransactionMoneyFormat.formatMoney(amountFrom));
        result.setTransactionDate(formatDate(transactionDate));
        result.setRemark(remark != null ? remark : "");
        result.setRateGroupId(rateGroupId);
        result.setLeg1Id(leg1Txn.getId());
        result.setLeg2Id(leg2Txn.getId());
        result.setMiddlemanId(middlemanRateTxnId != null ? middlemanRateTxnId : middlemanFeeTxnId);
        result.setMiddlemanRateId(middlemanRateTxnId);
        result.setMiddlemanFeeId(middlemanFeeTxnId);
        result.setExchangeRate(exchangeRate.stripTrailingZeros().toPlainString());
        result.setRateExpression(rateExpression != null ? rateExpression : "");
        return result;
    }

    private record MiddlemanSpec(
            Integer accountId,
            BigDecimal rate,
            BigDecimal feeInput,
            BigDecimal ratePortion,
            BigDecimal feePortion) {
        BigDecimal totalLeg2() {
            BigDecimal total = BigDecimal.ZERO;
            if (ratePortion != null) {
                total = total.add(ratePortion);
            }
            if (feePortion != null) {
                total = total.add(feePortion);
            }
            return total;
        }
    }

    /**
     * Middle-Man account required when rate and/or fee is set; either or both allowed.
     * Fee input is first-currency; converted as fee×exchangeRate for the fee leg.
     */
    private MiddlemanSpec resolveMiddleman(
            TransactionDTO.SubmitRequest request,
            Integer tenantId,
            FromToAccounts leg2,
            BigDecimal amountFrom,
            BigDecimal exchangeRate,
            BigDecimal grossTo) {
        Integer accountId = request.getMiddlemanAccountId();
        boolean hasAccount = accountId != null && accountId > 0;
        boolean hasRate = request.getMiddlemanRate() != null
                && request.getMiddlemanRate().compareTo(BigDecimal.ZERO) > 0;
        // middlemanAmount = fee input in first (leg1) currency
        boolean hasFee = request.getMiddlemanAmount() != null
                && request.getMiddlemanAmount().compareTo(BigDecimal.ZERO) > 0;

        if (!hasAccount && !hasRate && !hasFee) {
            return null;
        }
        if (!hasAccount) {
            throw new BusinessException("Middle-Man account is required when rate multiplier or fee is set");
        }
        if (!hasRate && !hasFee) {
            throw new BusinessException("Middle-Man requires rate multiplier and/or fee");
        }

        UserListDTO middlemanAccount = requireActiveAccount(accountId, tenantId, "Middle-Man account");
        requireAccountCurrency(tenantId, accountId, leg2.currency().getId(), middlemanAccount.getAccountId());

        BigDecimal rate = null;
        BigDecimal ratePortion = null;
        if (hasRate) {
            rate = request.getMiddlemanRate().setScale(RATE_SCALE, MONEY_ROUNDING);
            ratePortion = amountFrom.multiply(rate).setScale(MONEY_SCALE, MONEY_ROUNDING);
            if (ratePortion.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Middle-Man rate amount must be greater than zero");
            }
        }

        BigDecimal feeInput = null;
        BigDecimal feePortion = null;
        if (hasFee) {
            feeInput = request.getMiddlemanAmount().setScale(MONEY_SCALE, MONEY_ROUNDING);
            feePortion = feeInput.multiply(exchangeRate).setScale(MONEY_SCALE, MONEY_ROUNDING);
            if (feePortion.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Middle-Man fee must be greater than zero");
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        if (ratePortion != null) {
            total = total.add(ratePortion);
        }
        if (feePortion != null) {
            total = total.add(feePortion);
        }
        if (total.compareTo(grossTo) >= 0) {
            throw new BusinessException("Middle-Man total must be less than leg2 gross amount");
        }
        return new MiddlemanSpec(accountId, rate, feeInput, ratePortion, feePortion);
    }

    /** History remark on Fee / leg1 (toAccount1): {@code CHARGE MYR 10 SERVICE FEES}. */
    static String formatServiceFeeRemark(String currencyFromCode, BigDecimal feeInputFirstCcy) {
        if (feeInputFirstCcy == null || feeInputFirstCcy.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String ccy = currencyFromCode != null ? currencyFromCode.trim().toUpperCase(Locale.ROOT) : "";
        if (ccy.isEmpty()) {
            return null;
        }
        String feeDisplay = feeInputFirstCcy.stripTrailingZeros().toPlainString();
        return "CHARGE " + ccy + " " + feeDisplay + " SERVICE FEES";
    }

    private FromToAccounts requireFromToAccounts(
            Integer toAccountId,
            Integer fromAccountId,
            Integer currencyId,
            String currencyCode,
            Integer tenantId,
            String toLabel,
            String fromLabel) {
        if (toAccountId == null || toAccountId <= 0 || fromAccountId == null || fromAccountId <= 0) {
            throw new BusinessException(toLabel + " and " + fromLabel + " are required");
        }
        if (toAccountId.equals(fromAccountId)) {
            throw new BusinessException(toLabel + " and " + fromLabel + " must be different");
        }

        UserListDTO toAccount = requireActiveAccount(toAccountId, tenantId, toLabel);
        UserListDTO fromAccount = requireActiveAccount(fromAccountId, tenantId, fromLabel);

        Currency currency = resolveCurrency(tenantId, currencyId, currencyCode);
        requireAccountCurrency(tenantId, toAccountId, currency.getId(), toAccount.getAccountId());
        requireAccountCurrency(tenantId, fromAccountId, currency.getId(), fromAccount.getAccountId());
        return new FromToAccounts(toAccountId, fromAccountId, currency);
    }

    private record FromToAccounts(Integer toAccountId, Integer fromAccountId, Currency currency) {
    }

    private TransactionDTO.SubmitResult insertAndBuildResult(
            SessionUser session,
            Integer tenantId,
            Transaction.TransactionType transactionType,
            Integer toAccountId,
            Integer fromAccountId,
            Currency currency,
            BigDecimal amount,
            LocalDate transactionDate,
            String remark,
            String description,
            String rateGroupId) {
        Transaction txn = insertApproved(
                session, tenantId, transactionType, toAccountId, fromAccountId,
                currency.getId(), amount, transactionDate, remark, description, rateGroupId);

        TransactionDTO.SubmitResult result = new TransactionDTO.SubmitResult();
        result.setId(txn.getId());
        result.setTransactionType(transactionType.name());
        result.setTenantId(tenantId);
        result.setToAccountId(toAccountId);
        result.setFromAccountId(fromAccountId);
        result.setCurrencyCode(currency.getCode() != null
                ? currency.getCode().trim().toUpperCase(Locale.ROOT)
                : "");
        result.setAmount(TransactionMoneyFormat.formatMoney(amount));
        result.setTransactionDate(formatDate(transactionDate));
        result.setRemark(remark != null ? remark : "");
        return result;
    }

    private Transaction insertApproved(
            SessionUser session,
            Integer tenantId,
            Transaction.TransactionType transactionType,
            Integer toAccountId,
            Integer fromAccountId,
            Integer currencyId,
            BigDecimal amount,
            LocalDate transactionDate,
            String remark,
            String description,
            String rateGroupId) {
        String createdBy = session.login_id;
        LocalDateTime approvedAt = LocalDateTime.now();

        Transaction txn = new Transaction();
        txn.setTenantId(tenantId);
        txn.setTransactionType(transactionType);
        txn.setAccountId(toAccountId);
        txn.setFromAccountId(fromAccountId);
        txn.setCurrencyId(currencyId);
        txn.setAmount(amount);
        txn.setTransactionDate(transactionDate);
        txn.setDescription(description);
        txn.setRemark(remark);
        txn.setCreatedBy(createdBy);
        txn.setUpdatedBy(null);
        txn.setApprovalStatus(Transaction.ApprovalStatus.APPROVED);
        txn.setApprovedBy(createdBy);
        txn.setApprovedAt(approvedAt);
        txn.setBankProcessPostedId(null);
        txn.setRateGroupId(rateGroupId);

        transactionDao.insert(txn);
        return txn;
    }

    private static LocalDate resolveTransactionDate(TransactionDTO.SubmitRequest request) {
        if (request.getTransactionDate() != null && !request.getTransactionDate().isBlank()) {
            return TransactionDateParse.parseRequired(request.getTransactionDate(), "transactionDate");
        }
        return LocalDate.now();
    }

    private UserListDTO requireActiveAccount(Integer accountId, Integer tenantId, String label) {
        UserListDTO account = userDao.findUserByIdAndTenantId(accountId, tenantId);
        if (account == null) {
            throw new BusinessException(label + " not found");
        }
        if (account.getStatus() != null && account.getStatus() != User.AccountStatus.ACTIVE) {
            throw new BusinessException(label + " is not active");
        }
        return account;
    }

    private Currency resolveCurrency(Integer tenantId, Integer currencyId, String currencyCode) {
        if (currencyId != null && currencyId > 0) {
            Currency currency = currencyDao.findByIdAndTenantId(currencyId, tenantId);
            if (currency == null) {
                throw new BusinessException("Currency not found");
            }
            return currency;
        }
        String code = currencyCode != null ? currencyCode.trim().toUpperCase(Locale.ROOT) : "";
        if (code.isEmpty()) {
            throw new BusinessException("Currency is required");
        }
        Currency currency = currencyDao.findByTenantIdAndCode(tenantId, code);
        if (currency == null || currency.getId() == null) {
            throw new BusinessException("Currency not found: " + code);
        }
        return currency;
    }

    private void requireAccountCurrency(Integer tenantId, Integer accountId, Integer currencyId, String accountCode) {
        int linked = currencyDao.countAccountCurrencyLink(accountId, tenantId, currencyId);
        if (linked <= 0) {
            String code = accountCode != null ? accountCode.trim() : String.valueOf(accountId);
            throw new BusinessException("Account " + code + " does not support the selected currency");
        }
    }

    private static BigDecimal parsePositiveAmount(BigDecimal raw, String label) {
        if (raw == null) {
            throw new BusinessException(label + " is required");
        }
        BigDecimal amount = raw.setScale(MONEY_SCALE, MONEY_ROUNDING);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(label + " must be greater than zero");
        }
        return amount;
    }

    private static BigDecimal parsePositiveExchangeRate(BigDecimal raw) {
        if (raw == null) {
            throw new BusinessException("Exchange rate is required");
        }
        BigDecimal rate = raw.setScale(RATE_SCALE, MONEY_ROUNDING);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Exchange rate must be greater than zero");
        }
        return rate;
    }

    private static void validateRateAmounts(BigDecimal amountFrom, BigDecimal amountTo, BigDecimal exchangeRate) {
        BigDecimal expected = amountFrom.multiply(exchangeRate).setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal delta = expected.subtract(amountTo).abs();
        if (delta.compareTo(RATE_AMOUNT_TOLERANCE) > 0) {
            throw new BusinessException(
                    "Leg2 amount must equal leg1 amount × exchange rate (expected "
                            + TransactionMoneyFormat.formatMoney(expected) + ")");
        }
    }

    private static BigDecimal parseSignedNonZeroAmount(BigDecimal raw) {
        if (raw == null) {
            throw new BusinessException("Amount is required");
        }
        BigDecimal amount = raw.setScale(MONEY_SCALE, MONEY_ROUNDING);
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("Adjustment amount must be non-zero");
        }
        return amount;
    }

    private static String newRateGroupId() {
        return "RG-" + System.currentTimeMillis() + "-"
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatDate(LocalDate date) {
        return String.format(Locale.ROOT, "%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }
}
