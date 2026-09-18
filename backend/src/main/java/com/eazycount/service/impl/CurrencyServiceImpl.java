package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.AuditSnapshots;
import com.eazycount.audit.Audited;
import com.eazycount.entity.AuditLog;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.dto.UserLinkedDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.UserCurrency;
import com.eazycount.security.SessionUser;
import com.eazycount.service.CurrencyService;
import com.eazycount.util.AccessControlUtils;
import com.eazycount.util.AssertUtils;
import com.eazycount.util.NormalizeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CurrencyServiceImpl implements CurrencyService {

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private TransactionDao transactionDao;

    @Override
    public List<Currency> findCurrencyByTenantId(Integer tenantId) {
        AccessControlUtils.requireValidTenantId(tenantId);
        return currencyDao.findCurrencyByTenantId(tenantId);
    }

    @Override
    public List<UserCurrencyDTO> findAvailableCurrencies(Integer tenantId, Integer accountId) {
        AccessControlUtils.requireValidTenantId(tenantId);

        List<Currency> currencies = currencyDao.findCurrencyByTenantId(tenantId);
        Set<Integer> linkedIds = resolveLinkedCurrencyIds(accountId, tenantId);

        List<UserCurrencyDTO> result = new ArrayList<>(currencies.size());
        for (Currency currency : currencies) {
            if (currency == null || currency.getId() == null) {
                continue;
            }

            Currency.SourceType syncSource = currency.getSyncSource() != null
                    ? currency.getSyncSource()
                    : Currency.SourceType.MANUAL;
            boolean isSubsidiary = syncSource == Currency.SourceType.SUBSIDIARY;

            UserCurrencyDTO dto = new UserCurrencyDTO();
            dto.setId(currency.getId());
            dto.setCode(currency.getCode() != null ? currency.getCode().trim().toUpperCase() : "");
            dto.setLinked(linkedIds.contains(currency.getId()));
            dto.setSyncSource(syncSource.name());
            dto.setDeletable(!isSubsidiary);
            result.add(dto);
        }
        return result;
    }

    private Set<Integer> resolveLinkedCurrencyIds(Integer accountId, Integer tenantId) {
        if (accountId == null || accountId <= 0) {
            return Set.of();
        }
        List<Integer> linked = currencyDao.findCurrencyIdsByAccountIdAndTenantId(accountId, tenantId);
        return new HashSet<>(linked);
    }


    @Transactional
    @Override
    @Audited(module = "ACCOUNT", action = AuditLog.Action.CREATE,
            entityIdExpr = "#currency.id", sourceTable = "currency")
    public Currency addNewCurrency(Currency currency) {
        SessionUser session = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(session);
        if (currency == null || currency.getTenantId() == null) {
            throw new BusinessException("Invalid tenant id");
        }

        String code = currency.getCode() != null ? currency.getCode().trim().toUpperCase() : "";
        if (code.isBlank()) {
            throw new BusinessException("Currency code is required");
        }

        currency.setCode(code);
        if (currency.getSyncSource() == null) {
            currency.setSyncSource(Currency.SourceType.MANUAL);
        }
        if (currency.getStatus() == null) {
            currency.setStatus(Currency.Status.ACTIVE);
        }

        if (currencyDao.findDuplicateByTenantIDAndCode(Integer.parseInt(currency.getTenantId()), code) > 0) {
            throw new BusinessException("Duplicate currency code");
        }

        try {
            currencyDao.addNewCurrency(currency);
        } catch (Exception e) {
            throw new BusinessException("Insert Currency Failed!");
        }
        AuditContext.captureAfter(currency.getId(), AuditSnapshots.currency(currency));

        return currency;
    }

    @Transactional
    @Override
    @Audited(module = "ACCOUNT", action = AuditLog.Action.DELETE,
            entityIdExpr = "#id", sourceTable = "currency")
    public void deleteCurrencyByIdAndTenantId (Integer id, Integer tenantId) {
        SessionUser session = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(session);

        AssertUtils.requirePositive(id, "id");
        AccessControlUtils.requireValidTenantId(tenantId);

        Currency currency = AssertUtils.requireFound(
                currencyDao.findByIdAndTenantId(id, tenantId), "Currency not found or access denied");
        AuditContext.captureBefore(id, AuditSnapshots.currency(currency));
        List<UserLinkedDTO> accountsInUse = currencyDao.findLinkedAccountsByCurrencyIdAndTenantId(id, tenantId);
        if (accountsInUse != null && !accountsInUse.isEmpty()) {
            String labels = accountsInUse.stream()
                    .map(a -> {
                        String name = a.getName() != null ? a.getName().trim() : "";
                        String code = a.getAccountId() != null ? a.getAccountId().trim() : "";
                        if (!name.isEmpty() && !code.isEmpty()) return name + " (" + code + ")";
                        return !name.isEmpty() ? name : code;
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(", "));

            Map<String, Object> payload = Map.of("accounts_in_use", accountsInUse);
            throw new BusinessException("Cannot delete currency. The following accounts are using it: " + labels, payload);
        }

        if (transactionDao.countTransactionsByCurrencyId(id, tenantId) > 0) {
            throw new BusinessException("Currency has existing transaction in used cannot be deleted!");
        }

        try {
            currencyDao.deleteCurrencyByIdAndTenantId(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete currency");
        }
    }

    @Override
    public List<Integer> findCurrencyIdsByAccountIdAndTenantId(Integer accountId, Integer tenantId) {
        AssertUtils.requirePositive(accountId, "accountId");
        AccessControlUtils.requireValidTenantId(tenantId);

        return currencyDao.findCurrencyIdsByAccountIdAndTenantId(accountId, tenantId);
    }

    @Override
    @Audited(module = "ACCOUNT", action = AuditLog.Action.CREATE,
            entityIdExpr = "#accountId", sourceTable = "account_currency")
    public void insertAccountCurrency(int accountId, int tenantId, List<Integer> currencyIds) {
        AccessControlUtils.requireLoggedIn();

        List<Integer> ids = currencyIds == null ? List.of() :
                currencyIds.stream().filter(id -> id != null && id > 0).distinct().toList();

        if (ids.isEmpty()) {
            throw new BusinessException("At least one currency is required");
        }

        // 2. 校验都属于该 tenant
        int validCount = currencyDao.countValidCurrenciesForTenant(tenantId, ids);
        if (validCount != ids.size()) {
            throw new BusinessException("Invalid currency for tenant");
        }

        // 3. loop 单条 insert
        for (int i = 0; i < ids.size(); i++) {
            UserCurrency row = new UserCurrency();
            row.setAccountId(accountId);
            row.setTenantId(tenantId);
            row.setCurrencyId(ids.get(i));
            row.setSortOrder(i);
            currencyDao.insertAccountCurrency(row);
        }
        AuditContext.captureAfter(accountId, Map.of("currency_ids", ids));
    }

    @Override
    @Audited(module = "ACCOUNT", action = AuditLog.Action.DELETE,
            entityIdExpr = "#accountId", sourceTable = "account_currency")
    public void deleteByAccountIdAndTenantId(Integer accountId, Integer tenantId) {
        AssertUtils.requirePositive(accountId, "accountId");
        AccessControlUtils.requireValidTenantId(tenantId);

        AuditContext.captureBefore(accountId,
                Map.of("currency_ids", currencyDao.findCurrencyIdsByAccountIdAndTenantId(accountId, tenantId)));
        try{
            currencyDao.deleteByAccountIdAndTenantId(accountId, tenantId);
        }catch (Exception e){
            throw new BusinessException("Delete Currency Failed!");
        }
    }

    // List, Update Linked Account Currency
    @Override
    public UserLinkedDTO findLinkedAccountsByCurrencyIdAndTenantId(Integer currencyId, Integer tenantId) {
        AccessControlUtils.requireValidTenantId(tenantId);
        AssertUtils.requirePositive(currencyId, "currencyId");

        Currency currency = AssertUtils.requireFound(
                currencyDao.findByIdAndTenantId(currencyId, tenantId), "Currency not found or access denied");

        List<UserLinkedDTO> linkAcc = currencyDao.findLinkedAccountsByCurrencyIdAndTenantId(currencyId, tenantId);
        List<Integer> linkedIds = linkAcc.stream()
                .map(UserLinkedDTO::getId)
                .filter(id -> id != null && id > 0)
                .toList();

        UserLinkedDTO result = new UserLinkedDTO();
        result.setLinkedAccountIds(linkedIds);
        result.setLinkedAccounts(linkAcc);
        return result;
    }

    @Override
    @Transactional
    @Audited(module = "ACCOUNT", action = AuditLog.Action.UPDATE,
            entityIdExpr = "#request.currencyId", sourceTable = "account_currency")
    public void bulkUpdateAccountCurrency(UserLinkedDTO request) {
        SessionUser session = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(session);

        Integer tenantId = request.getTenantId();
        Integer currencyId = request.getCurrencyId();
        AccessControlUtils.requireValidTenantId(tenantId);
        AssertUtils.requirePositive(currencyId, "currencyId");

        AssertUtils.requireFound(
                currencyDao.findByIdAndTenantId(currencyId, tenantId), "Currency not found or access denied");

        List<Integer> toLink = NormalizeUtils.normalizeIds(request.getLinkedAccountIds());
        List<Integer> toUnlink = NormalizeUtils.normalizeIds(request.getUnlinkedAccountIds());
        // The method's own computed diff *is* the before/after here — no need to re-derive it
        // from a fresh DB read, since these two lists are exactly what's about to change.
        AuditContext.captureBefore(currencyId, Map.of("about_to_unlink_accounts", toUnlink));
        AuditContext.captureAfter(currencyId, Map.of("linked_accounts", toLink));

        List<Integer> allAccountIds = new ArrayList<>();
        allAccountIds.addAll(toLink);
        allAccountIds.addAll(toUnlink);
        allAccountIds = allAccountIds.stream().distinct().toList();

        if (!allAccountIds.isEmpty()) {
            int valid = currencyDao.countAccountsInTenant(tenantId, allAccountIds);
            if (valid != allAccountIds.size()) {
                throw new BusinessException("Invalid account for tenant");
            }
        }

        // 3. 先 unlink（并校验至少保留 1 种货币）
        for (Integer accountId : toUnlink) {
            int total = currencyDao.countByAccountIdAndTenantId(accountId, tenantId);
            if (total <= 1) {
                throw new BusinessException("At least one currency is required");
            }
            currencyDao.deleteByAccountIdAndTenantIdAndCurrencyId(accountId, tenantId, currencyId);
        }

        // 4. 再 link（幂等 + sort_order 追加）
        for (Integer accountId : toLink) {
            if (currencyDao.countAccountCurrencyLink(accountId, tenantId, currencyId) > 0) {
                continue;
            }
            Integer maxSort = currencyDao.maxSortOrderByAccountIdAndTenantId(accountId, tenantId);
            int nextSort = (maxSort == null ? -1 : maxSort) + 1;

            UserCurrency row = new UserCurrency();
            row.setAccountId(accountId);
            row.setTenantId(tenantId);
            row.setCurrencyId(currencyId);
            row.setSortOrder(nextSort);
            currencyDao.insertAccountCurrency(row);
        }
    }

}
