package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.dto.UserLinkedDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.UserCurrency;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.CurrencyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CurrencyServiceImpl implements CurrencyService {

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public List<Currency> findCurrencyByTenantId(Integer tenantId) {
        if(tenantId == null){
            throw new BusinessException("Invalid tenantId!");
        }
        return currencyDao.findCurrencyByTenantId(tenantId);
    }

    @Override
    public List<UserCurrencyDTO> findAvailableCurrencies(Integer tenantId, Integer accountId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenantId!");
        }

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
    public Currency addNewCurrency(Currency currency) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
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

        return currency;
    }

    @Transactional
    @Override
    public void deleteCurrencyByIdAndTenantId (Integer id, Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        if (id == null || id <= 0 || tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid request");
        }

        Currency currency = currencyDao.findByIdAndTenantId(id, tenantId);
        if (currency == null) {
            throw new BusinessException("Currency not found or access denied");
        }

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

            try {
                currencyDao.deleteCurrencyByIdAndTenantId(id, tenantId);
            } catch (Exception e) {
                throw new BusinessException("Delete Currency Failed!");
            }
        }
    }

    @Override
    public List<Integer> findCurrencyIdsByAccountIdAndTenantId(Integer accountId, Integer tenantId) {
        if(accountId == null){
            throw new BusinessException("Invalid accountId");
        }
        if(tenantId == null){
            throw new BusinessException("Invalid tenantId");
        }

        return currencyDao.findCurrencyIdsByAccountIdAndTenantId(accountId, tenantId);
    }

    @Override
    public void insertAccountCurrency(int accountId, int tenantId, List<Integer> currencyIds) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

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
    }

    @Override
    public void deleteByAccountIdAndTenantId(Integer accountId, Integer tenantId) {
        if(accountId == null){
            throw new BusinessException("Invalid accountId");
        }
        if(tenantId == null){
            throw new BusinessException("Invalid tenantId");
        }

        try{
            currencyDao.deleteByAccountIdAndTenantId(accountId, tenantId);
        }catch (Exception e){
            throw new BusinessException("Delete Currency Failed!");
        }
    }

    // List, Update Linked Account Currency
    @Override
    public UserLinkedDTO findLinkedAccountsByCurrencyIdAndTenantId(Integer currencyId, Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenantId!");
        }
        if (currencyId == null || currencyId <= 0) {
            throw new BusinessException("Invalid currencyId!");
        }

        Currency currency = currencyDao.findByIdAndTenantId(currencyId, tenantId);
        if (currency == null || currency.getId() == null) {
            throw new BusinessException("Currency not found or access denied");
        }

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
    public void bulkUpdateAccountCurrency(UserLinkedDTO request) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        Integer tenantId = request.getTenantId();
        Integer currencyId = request.getCurrencyId();
        if (tenantId == null || tenantId <= 0 || currencyId == null || currencyId <= 0) {
            throw new BusinessException("Invalid request");
        }

        if (currencyDao.findByIdAndTenantId(currencyId, tenantId) == null) {
            throw new BusinessException("Currency not found or access denied");
        }

        List<Integer> toLink = normalizeIds(request.getLinkedAccountIds());
        List<Integer> toUnlink = normalizeIds(request.getUnlinkedAccountIds());

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

    private List<Integer> normalizeIds(List<Integer> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }
}
