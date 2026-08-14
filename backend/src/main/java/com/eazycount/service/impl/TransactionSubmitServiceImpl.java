package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.TransactionRateDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionSubmitDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Transaction;
import com.eazycount.entity.TransactionRate;
import com.eazycount.entity.User;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionSubmitService;
import com.eazycount.util.RateMulCalculator;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TransactionSubmitServiceImpl implements TransactionSubmitService {

    static final String ADJUSTMENT_DESCRIPTION = "ADJUSTMENT - WIN/LOSS";

    /** Max |leg2 − leg1×rate| (and middleman net) allowed at RATE amount precision. */
    private static final BigDecimal RATE_AMOUNT_TOLERANCE =
            BigDecimal.ONE.movePointLeft(TransactionMoneyFormat.RATE_AMOUNT_SCALE);

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
    public TransactionSubmitDTO submit(TransactionSubmitDTO request) {
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

    private TransactionSubmitDTO submitTransfer(
            TransactionSubmitDTO request,
            SessionUser session,
            Integer tenantId,
            Transaction.TransactionType transactionType) {
        FromToAccounts accounts = requireFromToAccounts(
                request.getToAccountId(), request.getFromAccountId(),
                request.getCurrencyId(), request.getCurrencyCode(),
                tenantId, "To account", "From account");
        BigDecimal amount = parsePositiveAmount(request.getAmount(), "Amount");
        String description = formatTransferDescription(
                transactionType.name(), accounts.fromAccount(), accounts.toAccount());
        return insertAndBuildResult(
                session, tenantId, transactionType, accounts.toAccountId(), accounts.fromAccountId(),
                accounts.currency(), amount, resolveTransactionDate(request),
                trimToNull(request.getRemark()), description, null);
    }

    private TransactionSubmitDTO submitProfit(
            TransactionSubmitDTO request,
            SessionUser session,
            Integer tenantId) {
        FromToAccounts accounts = requireFromToAccounts(
                request.getToAccountId(), request.getFromAccountId(),
                request.getCurrencyId(), request.getCurrencyCode(),
                tenantId, "To account", "From account");
        BigDecimal amount = parsePositiveAmount(request.getAmount(), "Amount");
        String description = formatTransferDescription(
                Transaction.TransactionType.PROFIT.name(), accounts.fromAccount(), accounts.toAccount());
        return insertAndBuildResult(
                session, tenantId, Transaction.TransactionType.PROFIT,
                accounts.toAccountId(), accounts.fromAccountId(), accounts.currency(),
                amount, resolveTransactionDate(request), trimToNull(request.getRemark()), description, null);
    }

    private TransactionSubmitDTO submitAdjustment(
            TransactionSubmitDTO request,
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
     * Middle-Man: account required with rate multiplier and/or fee and/or platform fee (any subset).
     * Rate-Mul commission: RateMulCalculator.computeCommission — divide mode (/newDivisor, only
     * when FX itself is /divisor), or multiply mode (points x1000 when FX is /divisor, else
     * "new rate" diff (fxRate - mul) x leg1Amount). Can be negative (middleman underwater);
     * negative/zero commission is allowed but posts no ledger row (see resolveMiddleman).
     * Fee/Platform Fee are face values in the SECOND (leg2) currency, no FX conversion.
     * Fee portion = Fee − Platform Fee (net); posts only when > 0 — middleman-only +Win/Loss
     * (no counterparty). leg2 net = gross − (ratePortion(if>0) + feePortion(if>0)).
     */
    private TransactionSubmitDTO submitRate(TransactionSubmitDTO request, SessionUser session, Integer tenantId) {
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

        BigDecimal amountFrom = parsePositiveRateAmount(request.getLeg1Amount(), "Leg1 amount");
        BigDecimal amountTo = parsePositiveRateAmount(request.getLeg2Amount(), "Leg2 amount");
        BigDecimal exchangeRate = parsePositiveExchangeRate(request.getExchangeRate());
        BigDecimal grossTo = TransactionMoneyFormat.normalizeComputedRate(amountFrom.multiply(exchangeRate));
        String rateExpression = trimToNull(request.getRateExpression());

        MiddlemanSpec middleman = resolveMiddleman(request, tenantId, leg2, amountFrom, exchangeRate, rateExpression, grossTo);
        if (middleman == null) {
            validateRateAmounts(amountFrom, amountTo, exchangeRate);
        } else {
            BigDecimal expectedNet = TransactionMoneyFormat.normalizeComputedRate(
                    grossTo.subtract(middleman.totalLeg2()));
            BigDecimal delta = expectedNet.subtract(amountTo).abs();
            if (delta.compareTo(RATE_AMOUNT_TOLERANCE) > 0) {
                throw new BusinessException(
                        "Leg2 amount must equal (leg1 × exchange rate) − middleman total (expected "
                                + TransactionMoneyFormat.formatMoney(expectedNet) + ")");
            }
        }

        String remark = trimToNull(request.getRemark());
        LocalDate transactionDate = resolveTransactionDate(request);
        String rateGroupId = newRateGroupId();

        String leg1Ccy = leg1.currency().getCode() != null
                ? leg1.currency().getCode().trim().toUpperCase(Locale.ROOT)
                : "";
        String leg2Ccy = leg2.currency().getCode() != null
                ? leg2.currency().getCode().trim().toUpperCase(Locale.ROOT)
                : "";
        String rateToken = rateExpression != null
                ? rateExpression
                : exchangeRate.stripTrailingZeros().toPlainString();
        String exchPrefix = "EXCH RATE " + rateToken + " " + leg1Ccy + " "
                + TransactionMoneyFormat.formatMoney(amountFrom) + " > " + leg2Ccy;
        String leg1Description = exchPrefix + " | FROM " + accountDisplayName(leg1.fromAccount())
                + " TO " + accountDisplayName(leg1.toAccount());
        String leg2Description = exchPrefix + " | FROM " + accountDisplayName(leg2.fromAccount())
                + " TO " + accountDisplayName(leg2.toAccount());

        // When Fee is used: leg1 (toAccount1) History remark = CHARGE {ccy2} {feeInput} SERVICE FEES
        String serviceFeeRemark = null;
        if (middleman != null && middleman.feeInput() != null) {
            serviceFeeRemark = formatServiceFeeRemark(leg2Ccy, middleman.feeInput());
        }
        String leg1Remark = serviceFeeRemark != null ? serviceFeeRemark : remark;

        Transaction leg1Txn = insertApproved(
                session, tenantId, Transaction.TransactionType.RATE,
                leg1.toAccountId(), leg1.fromAccountId(), leg1.currency().getId(),
                amountFrom, transactionDate, leg1Remark, leg1Description, rateGroupId);
        Transaction leg2Txn = insertApproved(
                session, tenantId, Transaction.TransactionType.RATE,
                leg2.toAccountId(), leg2.fromAccountId(), leg2.currency().getId(),
                amountTo, transactionDate, remark, leg2Description, rateGroupId);

        Integer middlemanRateTxnId = null;
        Integer middlemanFeeTxnId = null;
        if (middleman != null) {
            String leg1ToName = accountDisplayName(leg1.toAccount());
            String amountText = TransactionMoneyFormat.formatMoney(amountFrom);
            // From=middleman (+WL), To=leg2 payer (−WL) — same signs as PROFIT; second currency.
            if (middleman.ratePortion() != null) {
                String rateMarkup = formatMiddlemanMarkupDescription(
                        false, middleman.parsedRate(), leg1Ccy, amountText, leg2Ccy, leg1ToName);
                Transaction rateTxn = insertApproved(
                        session, tenantId, Transaction.TransactionType.RATE,
                        leg2.toAccountId(), middleman.accountId(), leg2.currency().getId(),
                        middleman.ratePortion(), transactionDate, remark,
                        rateMarkup, rateGroupId);
                middlemanRateTxnId = rateTxn.getId();
            }
            if (middleman.feePortion() != null) {
                // Fee: middleman-only +Win/Loss (no −WL on leg2 payer — fee already in leg1 amount).
                String feeMarkup = formatMiddlemanMarkupDescription(
                        true, null, leg1Ccy, amountText, leg2Ccy, leg1ToName);
                Transaction feeTxn = insertApproved(
                        session, tenantId, Transaction.TransactionType.RATE,
                        middleman.accountId(), null, leg2.currency().getId(),
                        middleman.feePortion(), transactionDate, remark,
                        feeMarkup, rateGroupId);
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
            header.setMiddlemanRate(middleman.parsedRate() != null
                    ? (middleman.parsedRate().mode() == RateMulCalculator.Mode.DIVIDE
                            ? middleman.parsedRate().divisor()
                            : middleman.parsedRate().value())
                    : null);
            header.setMiddlemanRateExpression(middleman.rateRawInput());
            // Fee / Platform Fee stored as submitted face values (currency_to; not converted).
            header.setMiddlemanAmount(middleman.feeInput());
            header.setPlatformFeeAmount(middleman.platformFeeInput());
        } else {
            header.setMiddlemanAccountId(null);
            header.setMiddlemanRate(null);
            header.setMiddlemanRateExpression(null);
            header.setMiddlemanAmount(null);
            header.setPlatformFeeAmount(null);
        }
        transactionRateDao.insert(header);

        TransactionSubmitDTO result = new TransactionSubmitDTO();
        result.setId(leg1Txn.getId());
        result.setTransactionType(Transaction.TransactionType.RATE.name());
        result.setTenantId(tenantId);
        result.setToAccountId(leg1.toAccountId());
        result.setFromAccountId(leg1.fromAccountId());
        result.setCurrencyCode(leg1.currency().getCode() != null
                ? leg1.currency().getCode().trim().toUpperCase(Locale.ROOT)
                : "");
        result.setAmountDisplay(TransactionMoneyFormat.formatMoney(amountFrom));
        result.setTransactionDate(formatDate(transactionDate));
        result.setRemark(remark != null ? remark : "");
        result.setRateGroupId(rateGroupId);
        result.setLeg1Id(leg1Txn.getId());
        result.setLeg2Id(leg2Txn.getId());
        result.setMiddlemanId(middlemanRateTxnId != null ? middlemanRateTxnId : middlemanFeeTxnId);
        result.setMiddlemanRateId(middlemanRateTxnId);
        result.setMiddlemanFeeId(middlemanFeeTxnId);
        result.setExchangeRateDisplay(exchangeRate.stripTrailingZeros().toPlainString());
        result.setRateExpression(rateExpression != null ? rateExpression : "");
        return result;
    }

    private record MiddlemanSpec(
            Integer accountId,
            RateMulCalculator.ParsedRate parsedRate,
            String rateRawInput,
            BigDecimal feeInput,
            BigDecimal platformFeeInput,
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

    /*
     * Middle Man account function set. Account required when rate multiplier and/or fee and/or
     * platform fee are set — any subset is allowed, not all three.
     * Rate-Mul commission and (fee − platform fee) are computed independently; each only posts
     * a ledger row when its own value is > 0 (negative/zero is allowed — silently not posted).
     */
    private MiddlemanSpec resolveMiddleman(
            TransactionSubmitDTO request,
            Integer tenantId,
            FromToAccounts leg2,
            BigDecimal amountFrom,
            BigDecimal exchangeRate,
            String rateExpression,
            BigDecimal grossTo) {
        Integer accountId = request.getMiddlemanAccountId();
        boolean hasAccount = accountId != null && accountId > 0;

        String rateRawInput = trimToNull(request.getMiddlemanRateExpression());
        if (rateRawInput == null && request.getMiddlemanRate() != null) {
            rateRawInput = request.getMiddlemanRate().stripTrailingZeros().toPlainString();
        }
        boolean hasRateInput = rateRawInput != null;

        // middlemanAmount = Service Fee face value in second (leg2) currency; no FX conversion.
        boolean hasFee = request.getMiddlemanAmount() != null
                && request.getMiddlemanAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean hasPlatformFee = request.getPlatformFeeAmount() != null
                && request.getPlatformFeeAmount().compareTo(BigDecimal.ZERO) > 0;

        if (!hasAccount && !hasRateInput && !hasFee && !hasPlatformFee) {
            return null;
        }
        if (!hasAccount) {
            throw new BusinessException("Middle-Man account is required when rate multiplier, fee, or platform fee is set");
        }
        if (!hasRateInput && !hasFee && !hasPlatformFee) {
            throw new BusinessException("Middle-Man requires rate multiplier, fee, and/or platform fee");
        }

        UserListDTO middlemanAccount = requireActiveAccount(accountId, tenantId, "Middle-Man account");
        requireAccountCurrency(tenantId, accountId, leg2.currency().getId(), middlemanAccount.getAccountId());

        RateMulCalculator.ParsedRate parsedRate = null;
        BigDecimal rateMulCommission = BigDecimal.ZERO;
        if (hasRateInput) {
            parsedRate = RateMulCalculator.parseMiddlemanRateInput(rateRawInput);
            if (!parsedRate.valid()) {
                throw new BusinessException("Please enter a valid Middle-Man rate multiplier");
            }
            // Same ≤8dp rule as every other RATE-scale input (schema column is DECIMAL(18,8)).
            BigDecimal rateMagnitude = parsedRate.mode() == RateMulCalculator.Mode.DIVIDE
                    ? parsedRate.divisor()
                    : parsedRate.value();
            TransactionMoneyFormat.requireMaxScale(
                    rateMagnitude, TransactionMoneyFormat.RATE_AMOUNT_SCALE, "Middle-Man rate");
            rateMulCommission = TransactionMoneyFormat.normalizeComputedRate(
                    RateMulCalculator.computeCommission(amountFrom, rateRawInput, rateExpression, exchangeRate));
        }

        BigDecimal feeInput = hasFee
                ? parsePositiveRateAmount(request.getMiddlemanAmount(), "Middle-Man fee")
                : null;
        BigDecimal platformFeeInput = hasPlatformFee
                ? parsePositiveRateAmount(request.getPlatformFeeAmount(), "Platform fee")
                : null;
        BigDecimal feeNet = TransactionMoneyFormat.normalizeComputedRate(
                TransactionMoneyFormat.nz(feeInput).subtract(TransactionMoneyFormat.nz(platformFeeInput)));

        // Negative/zero commission (middleman underwater) or net fee (platform fee ate it all)
        // is allowed but posts no ledger row — see class comment on submitRate().
        BigDecimal ratePortion = rateMulCommission.compareTo(BigDecimal.ZERO) > 0 ? rateMulCommission : null;
        BigDecimal feePortion = feeNet.compareTo(BigDecimal.ZERO) > 0 ? feeNet : null;

        BigDecimal total = TransactionMoneyFormat.add(ratePortion, feePortion);
        if (total.compareTo(grossTo) >= 0) {
            throw new BusinessException("Middle-Man total must be less than leg2 gross amount");
        }
        return new MiddlemanSpec(accountId, parsedRate, rateRawInput, feeInput, platformFeeInput, ratePortion, feePortion);
    }

    /** History remark on Fee / leg1 (toAccount1): {@code CHARGE MYR 10 SERVICE FEES} (currency_to face value). */
    static String formatServiceFeeRemark(String currencyToCode, BigDecimal feeInputSecondCcy) {
        if (feeInputSecondCcy == null || feeInputSecondCcy.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String ccy = currencyToCode != null ? currencyToCode.trim().toUpperCase(Locale.ROOT) : "";
        if (ccy.isEmpty()) {
            return null;
        }
        String feeDisplay = feeInputSecondCcy.stripTrailingZeros().toPlainString();
        return "CHARGE " + ccy + " " + feeDisplay + " SERVICE FEES";
    }

    /* Type Format Description Only except "ADJUSTMENT", "RATE". E.g. CONTRA FROM "fromName" TO "toName" */
    static String formatTransferDescription(String type, UserListDTO fromAccount, UserListDTO toAccount) {
        String typeToken = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        return typeToken + " FROM " + accountDisplayName(fromAccount) + " TO " + accountDisplayName(toAccount);
    }

    /*
     * Middle man description only. Fee: {MARKUP X MYR 1010 > SGD | FROM {leg1ToName}},
     * Rate divide mode: {MARKUP /1.55 MYR 1010 > SGD | FROM {leg1ToName}},
     * Rate multiply mode: {MARKUP x2.93 MYR 1010 > SGD | FROM {leg1ToName}}.
     */
    static String formatMiddlemanMarkupDescription(
            boolean feeKind,
            RateMulCalculator.ParsedRate parsedRate,
            String ccy1,
            String amountText,
            String ccy2,
            String leg1ToName) {
        String rateToken;
        if (feeKind) {
            rateToken = "X";
        } else if (parsedRate != null && parsedRate.valid()) {
            rateToken = parsedRate.mode() == RateMulCalculator.Mode.DIVIDE
                    ? "/" + parsedRate.divisor().stripTrailingZeros().toPlainString()
                    : "x" + parsedRate.value().stripTrailingZeros().toPlainString();
        } else {
            rateToken = "";
        }
        StringBuilder sb = new StringBuilder("MARKUP");
        if (!rateToken.isEmpty()) {
            sb.append(' ').append(rateToken);
        }
        if (ccy1 != null && !ccy1.isBlank() && ccy2 != null && !ccy2.isBlank()) {
            sb.append(' ').append(ccy1.trim().toUpperCase(Locale.ROOT));
            if (amountText != null && !amountText.isBlank()) {
                sb.append(' ').append(amountText.trim());
            }
            sb.append(" > ").append(ccy2.trim().toUpperCase(Locale.ROOT));
        }
        if (leg1ToName != null && !leg1ToName.isBlank()) {
            sb.append(" | FROM ").append(leg1ToName.trim());
        }
        return sb.toString();
    }

    static String accountDisplayName(UserListDTO account) {
        if (account == null) {
            return "";
        }
        String name = account.getName() != null ? account.getName().trim() : "";
        if (!name.isEmpty()) {
            return name;
        }
        return account.getAccountId() != null ? account.getAccountId().trim() : "";
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
        return new FromToAccounts(toAccountId, fromAccountId, currency, toAccount, fromAccount);
    }

    private record FromToAccounts(
            Integer toAccountId,
            Integer fromAccountId,
            Currency currency,
            UserListDTO toAccount,
            UserListDTO fromAccount) {
    }

    private TransactionSubmitDTO insertAndBuildResult(
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

        TransactionSubmitDTO result = new TransactionSubmitDTO();
        result.setId(txn.getId());
        result.setTransactionType(transactionType.name());
        result.setTenantId(tenantId);
        result.setToAccountId(toAccountId);
        result.setFromAccountId(fromAccountId);
        result.setCurrencyCode(currency.getCode() != null
                ? currency.getCode().trim().toUpperCase(Locale.ROOT)
                : "");
        result.setAmountDisplay(TransactionMoneyFormat.formatMoney(amount));
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

    private static LocalDate resolveTransactionDate(TransactionSubmitDTO request) {
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
        BigDecimal amount = TransactionMoneyFormat.requireNormalAmount(raw, label);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(label + " must be greater than zero");
        }
        return amount;
    }

    private static BigDecimal parsePositiveRateAmount(BigDecimal raw, String label) {
        BigDecimal amount = TransactionMoneyFormat.requireRateAmount(raw, label);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(label + " must be greater than zero");
        }
        return amount;
    }

    private static BigDecimal parsePositiveExchangeRate(BigDecimal raw) {
        BigDecimal rate = TransactionMoneyFormat.requireMaxScale(
                raw, TransactionMoneyFormat.RATE_AMOUNT_SCALE, "Exchange rate");
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Exchange rate must be greater than zero");
        }
        return rate;
    }

    private static void validateRateAmounts(BigDecimal amountFrom, BigDecimal amountTo, BigDecimal exchangeRate) {
        BigDecimal expected = TransactionMoneyFormat.normalizeComputedRate(amountFrom.multiply(exchangeRate));
        BigDecimal delta = expected.subtract(amountTo).abs();
        if (delta.compareTo(RATE_AMOUNT_TOLERANCE) > 0) {
            throw new BusinessException(
                    "Leg2 amount must equal leg1 amount × exchange rate (expected "
                            + TransactionMoneyFormat.formatMoney(expected) + ")");
        }
    }

    private static BigDecimal parseSignedNonZeroAmount(BigDecimal raw) {
        BigDecimal amount = TransactionMoneyFormat.requireNormalAmount(raw, "Amount");
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
