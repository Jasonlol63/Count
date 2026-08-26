package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.MemberPageDTO;
import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;
import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.CurrencyService;
import com.eazycount.service.TransactionHistoryService;
import com.eazycount.service.UserPageService;
import com.eazycount.service.UserService;
import com.eazycount.dao.TenantDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserPageServiceImpl implements UserPageService {

    @Autowired
    private UserDao userDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private UserService userService;

    @Autowired
    private CurrencyService currencyService;

    @Autowired
    private TransactionHistoryService transactionHistoryService;

    @Override
    public TransactionHistoryResult getMemberHistory(TransactionHistoryRequest request) {
        SessionUser session = requireMemberSession();
        TransactionHistoryRequest scoped = request != null ? request : new TransactionHistoryRequest();
        int targetAccountId = resolveViewableAccountId(session, scoped.getAccountId());

        scoped.setAccountId(targetAccountId);
        scoped.setTenantId(session.tenant_id);

        return transactionHistoryService.historyList(scoped);
    }

    @Override
    public List<UserCurrencyDTO> getMemberAccountCurrencies(Integer accountId) {
        SessionUser session = requireMemberSession();
        int targetAccountId = resolveViewableAccountId(session, accountId);

        return currencyService.findAvailableCurrencies(session.tenant_id, targetAccountId).stream()
                .filter(UserCurrencyDTO::isLinked)
                .toList();
    }

    //Resolves which account for a member is allowed to view: themselves, or one visible via Account Link. */
    private int resolveViewableAccountId(SessionUser session, Integer requestedAccountId) {
        int loginAccountId = session.user_id;
        if (requestedAccountId == null || requestedAccountId <= 0 || requestedAccountId == loginAccountId) {
            return loginAccountId;
        }
        if (!visibleAccountIds(session).contains(requestedAccountId)) {
            throw new BusinessException("Unauthorized account access");
        }
        return requestedAccountId;
    }

    private Set<Integer> visibleAccountIds(SessionUser session) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(session.user_id);
        userService.getAllLinkedAccounts(session.user_id, session.tenant_id)
                .forEach(a -> { if (a.getId() != null) ids.add(a.getId()); });
        return ids;
    }

    @Override
    public List<MemberPageDTO.AccountCurrencies> getMemberAccountsCurrencies(List<Integer> accountIds) {
        SessionUser session = requireMemberSession();
        List<Integer> ids = accountIds == null
                ? List.of()
                : accountIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Set<Integer> visible = visibleAccountIds(session);
        List<MemberPageDTO.AccountCurrencies> result = new ArrayList<>();
        for (Integer accountId : ids) {
            if (!visible.contains(accountId)) {
                continue;
            }
            List<UserCurrencyDTO> currencies = currencyService.findAvailableCurrencies(session.tenant_id, accountId).stream()
                    .filter(UserCurrencyDTO::isLinked)
                    .toList();
            result.add(new MemberPageDTO.AccountCurrencies(accountId, currencies));
        }
        return result;
    }

    @Override
    public List<MemberPageDTO.AccountCurrencyBalance> getMemberMiniGridBalances(MemberPageDTO.BatchRequest request) {
        SessionUser session = requireMemberSession();
        if (request == null) {
            return List.of();
        }
        List<Integer> ids = request.getAccountIds() == null
                ? List.of()
                : request.getAccountIds().stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Set<Integer> visible = visibleAccountIds(session);
        List<MemberPageDTO.AccountCurrencyBalance> result = new ArrayList<>();
        for (Integer accountId : ids) {
            if (!visible.contains(accountId)) {
                continue;
            }
            TransactionHistoryRequest historyRequest = new TransactionHistoryRequest();
            historyRequest.setTenantId(session.tenant_id);
            historyRequest.setAccountId(accountId);
            historyRequest.setDateFrom(request.getDateFrom());
            historyRequest.setDateTo(request.getDateTo());
            historyRequest.setCurrencyCodes(request.getCurrencyCodes());

            TransactionHistoryResult history = transactionHistoryService.historyList(historyRequest);
            Map<String, String> lastBalanceByCurrency = new LinkedHashMap<>();
            for (TransactionHistoryResult.Row row : history.getHistory()) {
                String currency = row.getCurrency();
                String balance = row.getBalance();
                if (currency == null || currency.isBlank() || balance == null || balance.isBlank() || "-".equals(balance)) {
                    continue;
                }
                lastBalanceByCurrency.put(currency.toUpperCase(), balance);
            }
            lastBalanceByCurrency.forEach((currency, balance) ->
                    result.add(new MemberPageDTO.AccountCurrencyBalance(accountId, currency, balance)));
        }
        return result;
    }

    @Override
    public MemberPageDTO getMemberProfile() {
        SessionUser session = requireMemberSession();

        int accountId = session.user_id;
        int tenantId = session.tenant_id;

        UserListDTO account = userDao.findUserByIdAndTenantId(accountId, tenantId);
        if (account == null) {
            throw new BusinessException("Account not found");
        }

        List<UserListDTO> linkedAccounts = userService.getAllLinkedAccounts(accountId, tenantId);
        boolean hasAccountLink = linkedAccounts.size() > 1;

        MemberPageDTO profile = new MemberPageDTO();
        profile.setAccountId(accountId);
        profile.setAccountCode(account.getAccountId());
        profile.setAccountName(account.getName());
        profile.setTenantId(tenantId);
        profile.setHasAccountLink(hasAccountLink);

        if (hasAccountLink) {
            profile.setLinkedAccounts(linkedAccounts.stream()
                    .map(a -> new MemberPageDTO.LinkedAccount(a.getId(), a.getAccountId(), a.getName()))
                    .toList());
        } else {
            Tenant tenant = tenantDao.findTenantById(tenantId);
            if (tenant == null) {
                throw new BusinessException("Company not found");
            }
            profile.setTenantCode(tenant.getCode());
            profile.setTenantName(tenant.getName());
            profile.setCurrencies(currencyService.findAvailableCurrencies(tenantId, accountId).stream()
                    .filter(UserCurrencyDTO::isLinked)
                    .toList());
        }

        return profile;
    }

    private SessionUser requireMemberSession() {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (!"member".equalsIgnoreCase(session.user_type)) {
            throw new BusinessException("Not a member session");
        }
        return session;
    }
}
