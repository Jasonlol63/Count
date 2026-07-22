package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Transaction;
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

@Service
public class TransactionSubmitServiceImpl implements TransactionSubmitService {

    static final String ADJUSTMENT_DESCRIPTION = "ADJUSTMENT - WIN/LOSS";

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private static final Set<String> TRANSFER_TYPES = Set.of(
            Transaction.TransactionType.PAYMENT.name(),
            Transaction.TransactionType.CLAIM.name(),
            Transaction.TransactionType.CLEAR.name(),
            Transaction.TransactionType.CONTRA.name());

    @Autowired
    private TransactionDao transactionDao;

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
        Integer toAccountId = request.getToAccountId();
        Integer fromAccountId = request.getFromAccountId();
        if (toAccountId == null || toAccountId <= 0 || fromAccountId == null || fromAccountId <= 0) {
            throw new BusinessException("To account and From account are required");
        }
        if (toAccountId.equals(fromAccountId)) {
            throw new BusinessException("To account and From account must be different");
        }

        UserListDTO toAccount = requireActiveAccount(toAccountId, tenantId, "To account");
        UserListDTO fromAccount = requireActiveAccount(fromAccountId, tenantId, "From account");

        Currency currency = resolveCurrency(tenantId, request.getCurrencyId(), request.getCurrencyCode());
        requireAccountCurrency(tenantId, toAccountId, currency.getId(), toAccount.getAccountId());
        requireAccountCurrency(tenantId, fromAccountId, currency.getId(), fromAccount.getAccountId());

        BigDecimal amount = parsePositiveAmount(request.getAmount());
        return insertAndBuildResult(
                session, tenantId, transactionType, toAccountId, fromAccountId, currency,
                amount, resolveTransactionDate(request), trimToNull(request.getRemark()), null);
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
                amount, resolveTransactionDate(request), trimToNull(request.getRemark()), ADJUSTMENT_DESCRIPTION);
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
            String description) {
        String createdBy = session.login_id;
        LocalDateTime approvedAt = LocalDateTime.now();

        Transaction txn = new Transaction();
        txn.setTenantId(tenantId);
        txn.setTransactionType(transactionType);
        txn.setAccountId(toAccountId);
        txn.setFromAccountId(fromAccountId);
        txn.setCurrencyId(currency.getId());
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

        transactionDao.insert(txn);

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

    private static BigDecimal parsePositiveAmount(BigDecimal raw) {
        if (raw == null) {
            throw new BusinessException("Amount is required");
        }
        BigDecimal amount = raw.setScale(MONEY_SCALE, MONEY_ROUNDING);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Amount must be greater than zero");
        }
        return amount;
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
