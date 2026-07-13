package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.UserLink;
import com.eazycount.entity.User;
import com.eazycount.entity.UserTenantAccess;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.CurrencyService;
import com.eazycount.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserServiceImpl implements UserService {

    private static final Set<String> ALLOWED_ACCOUNT_LEDGER_ROLES = Set.of(
            "CAPITAL", "BANK", "CASH", "PROFIT", "EXPENSES", "COMPANY",
            "PARTNER", "STAFF", "SUPPLIER", "UPLINE", "AGENT", "MEMBER", "DEBTOR");

    @Autowired
    private UserDao userDao;

    @Autowired
    private CurrencyService currencyService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public List<UserListDTO> findUserByTenantId(Integer tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant ID not found!");
        }
        return userDao.findUserByTenantId(tenantId);
    }

    private String normalizeAccountLedgerRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BusinessException("Role is required");
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if ("PARTHER".equals(normalized)) {
            normalized = "PARTNER";
        }
        if (!ALLOWED_ACCOUNT_LEDGER_ROLES.contains(normalized)) {
            throw new BusinessException("Invalid role selected");
        }
        return normalized;
    }

    @Override
    @Transactional
    public UserListDTO createUser(UserListDTO userListDTO) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new com.eazycount.common.BusinessException("Not logged in");
        }
        if (userListDTO == null) {
            throw new BusinessException("Invalid request");
        }

        Integer tenantId = userListDTO.getScopeTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        User user = new User();
        user.setAccountId(userListDTO.getAccountId());
        user.setName(userListDTO.getName());
        user.setRole(normalizeAccountLedgerRole(userListDTO.getRole()));
        user.setPassword(passwordEncoder.encode(userListDTO.getPassword()));
        user.setPaymentAlert(userListDTO.getPaymentAlert());
        user.setAlertDay(userListDTO.getAlertDay());
        user.setAlertAmount(userListDTO.getAlertAmount());
        user.setAlertSpecificDate(userListDTO.getAlertSpecificDate());
        user.setRemark(userListDTO.getRemark());
        user.setStatus(userListDTO.getStatus());
        if (userListDTO.getStatus() == null) {
            user.setStatus(User.AccountStatus.ACTIVE);
        }

        if (user.getPaymentAlert() == null) {
            user.setPaymentAlert(0);
        }

        try {
            userDao.addUserDetails(user);
        } catch (Exception e) {
            throw new BusinessException("Create user failed!");
        }

        if (user.getId() == null || user.getId() <= 0) {
            throw new BusinessException("Create user failed!");
        }

        UserTenantAccess userTenantAccess = new UserTenantAccess();
        userTenantAccess.setAccountId(user.getId());
        userTenantAccess.setTenantId(userListDTO.getScopeTenantId());

        try {
            userDao.insertAccountTenantAccess(userTenantAccess);
        } catch (Exception e) {
            throw new BusinessException("Create user tenant access failed!");
        }

        currencyService.insertAccountCurrency(
                user.getId(),
                tenantId,
                userListDTO.getCurrencyIds());

        userListDTO.setId(user.getId());
        userListDTO.setTenantAccessId(userTenantAccess.getId());
        userListDTO.setScopeTenantId(userListDTO.getScopeTenantId());
        return userListDTO;

    }

    @Override
    @Transactional
    public UserListDTO updateUser(UserListDTO userListDTO) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (userListDTO == null
                || userListDTO.getId() == null || userListDTO.getId() <= 0
                || userListDTO.getScopeTenantId() == null || userListDTO.getScopeTenantId() <= 0) {
            throw new BusinessException("Invalid request");
        }

        UserListDTO existing = userDao.findUserByIdAndTenantId(userListDTO.getId(), userListDTO.getScopeTenantId());
        if (existing == null) {
            throw new BusinessException("User not found!");
        }

        Integer tenantId = userListDTO.getScopeTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        try {
            User user = new User();
            user.setId(userListDTO.getId());
            user.setName(userListDTO.getName());
            user.setRole(normalizeAccountLedgerRole(userListDTO.getRole()));
            if (userListDTO.getPassword() != null && !userListDTO.getPassword().isBlank()) {
                user.setPassword(passwordEncoder.encode(userListDTO.getPassword()));
            } else {
                user.setPassword(existing.getPassword());
            }
            user.setPaymentAlert(userListDTO.getPaymentAlert());
            user.setAlertDay(userListDTO.getAlertDay());
            user.setAlertAmount(userListDTO.getAlertAmount());
            user.setAlertSpecificDate(userListDTO.getAlertSpecificDate());
            user.setRemark(userListDTO.getRemark());
            userDao.updateUserDetails(user);
        } catch (Exception e) {
            throw new BusinessException("Update User failed!");
        }

        try {
            UserTenantAccess userTenantAccess = new UserTenantAccess();
            userTenantAccess.setAccountId(userListDTO.getId());
            userTenantAccess.setTenantId(userListDTO.getScopeTenantId());
            userDao.updateAccountTenantAccess(userTenantAccess);
        } catch (Exception e) {
            throw new BusinessException("Update UserTenantAccess failed!");
        }
        UserListDTO updated = userDao.findUserByIdAndTenantId(userListDTO.getId(), userListDTO.getScopeTenantId());
        if (updated == null) {
            throw new BusinessException("User not found after update!");
        }

        currencyService.deleteByAccountIdAndTenantId(
                userListDTO.getId(),
                userListDTO.getScopeTenantId());
        currencyService.insertAccountCurrency(
                userListDTO.getId(),
                userListDTO.getScopeTenantId(),
                userListDTO.getCurrencyIds());

        return updated;
    }

    @Transactional
    @Override
    public UserListDTO updateStatusByUserId(Integer userId, Integer scopeTenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (userId == null || userId <= 0 || scopeTenantId == null || scopeTenantId <= 0) {
            throw new BusinessException("Invalid request");
        }
        if (session.user_id.equals(userId)) {
            throw new BusinessException("You cannot toggle your own status");
        }

        try {
            UserListDTO user = userDao.findUserByIdAndTenantId(userId, scopeTenantId);
            if (user == null) {
                throw new BusinessException("User not found!");
            }
            if (user.getTenantAccessId() == null) {
                throw new BusinessException("UserTenantAccess not found!");
            }

            User.AccountStatus currentStatus;
            if (user.getStatus() != null) {
                currentStatus = user.getStatus();
            } else {
                currentStatus = User.AccountStatus.ACTIVE;
            }

            User.AccountStatus newStatus;
            if (currentStatus == User.AccountStatus.ACTIVE) {
                newStatus = User.AccountStatus.INACTIVE;
            } else {
                newStatus = User.AccountStatus.ACTIVE;
            }
            userDao.updateStatusByUserId(userId, newStatus);

        } catch (Exception e) {
            throw new BusinessException("Update User failed!");
        }

        UserListDTO result = userDao.findUserByIdAndTenantId(userId, scopeTenantId);
        if (result == null) {
            throw new com.eazycount.common.BusinessException(
                    "Status updated, but user is no longer visible in this tenant");
        }

        return result;
    }

    @Override
    @Transactional
    public void deleteUserByIdAndStatus(Integer id, Integer scopeTenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new com.eazycount.common.BusinessException("Not logged in");
        }

        if (id == null || id <= 0 || scopeTenantId == null || scopeTenantId <= 0) {
            throw new BusinessException("Invalid request");
        }

        UserListDTO existing = userDao.findUserByIdAndTenantId(id, scopeTenantId);
        if (existing == null) {
            throw new BusinessException("User not found!");
        }
        if (existing.getStatus() == User.AccountStatus.ACTIVE) {
            throw new BusinessException("User is not inactive, cannot be deleted!");
        }

        try {
            userDao.deleteUserTenantAccessByAccountIdAndTenantId(id, scopeTenantId);
        } catch (Exception e) {
            throw new BusinessException("Delete UserTenantAccess failed!");
        }

        try {
            userDao.deleteUserByIdAndStatus(id, User.AccountStatus.INACTIVE);
        } catch (Exception e) {
            throw new BusinessException("Delete User failed!");
        }
    }

    /* Account Link Side */
    @Override
    public void insertAccountLink(UserLink userLink) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (userLink == null) {
            throw new BusinessException("Invalid request");
        }

        final int tenantId = session.tenant_id;
        if (tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        int a = userLink.getAccountId1();
        int b = userLink.getAccountId2();
        if (a <= 0 || b <= 0) {
            throw new BusinessException("Invalid account id");
        }
        if (a == b) {
            throw new BusinessException("Cannot link the same account");
        }

        final int a1 = Math.min(a, b);
        final int a2 = Math.max(a, b);
        // 校验两端账号都属于该租户
        if (userDao.findUserByIdAndTenantId(a1, tenantId) == null) {
            throw new BusinessException("User A not found in tenant!");
        }
        if (userDao.findUserByIdAndTenantId(a2, tenantId) == null) {
            throw new BusinessException("User B not found in tenant!");
        }

        UserLink.LinkType linkType = userLink.getLinkType();
        if (linkType == null) {
            linkType = UserLink.LinkType.BIDIRECTIONAL; // 默认双向
        }

        Integer source = userLink.getSourceAccountId();
        if (linkType == UserLink.LinkType.UNIDIRECTIONAL) {
            if (source == null || (source != a1 && source != a2)) {
                throw new BusinessException("sourceAccountId must be one of the pair for UNIDIRECTIONAL");
            }
        } else { // BIDIRECTIONAL
            source = null; // 双向时应为 null
        }

        List<UserLink> existing = userDao.findByPair(a1, a2, tenantId);
        if (existing != null && !existing.isEmpty()) {
            throw new BusinessException("Accounts are already linked in this tenant");
        }

        UserLink accLink = new UserLink();
        accLink.setAccountId1(a1);
        accLink.setAccountId2(a2);
        accLink.setTenantId(tenantId);
        accLink.setLinkType(linkType);
        accLink.setSourceAccountId(source);
        try {
            userDao.insertAccountLink(accLink);
        } catch (Exception e) {
            throw new BusinessException("Insert Account Link failed!");
        }
    }

    @Override
    @Transactional
    public void deleteAccountLinkById(long id) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (id <= 0) {
            throw new BusinessException("Invalid link id");
        }
        try {
            userDao.deleteById(id, session.tenant_id);
        } catch (Exception e) {
            throw new BusinessException("Delete Account Link failed!");
        }
    }

    /* Account Link - Delete by AccountId (all links of one account in tenant) */
    @Override
    @Transactional
    public void deleteAccountLinkByAccountId(int accountId, int tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (accountId <= 0 || tenantId <= 0) {
            throw new BusinessException("Invalid request");
        }
        if (tenantId != session.tenant_id) {
            throw new BusinessException("Unauthorized tenant access");
        }
        try {
            userDao.deleteByAccountId(accountId, session.tenant_id);
        } catch (Exception e) {
            throw new BusinessException("Delete Account Link by Account failed!");
        }
    }

    @Override
    @Transactional
    public void updateAccountLink(UserLink userLink) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (userLink == null) {
            throw new BusinessException("Invalid request");
        }

        // If ID is provided, use it for deletion
        if (userLink.getId() != null && userLink.getId() > 0) {
            try {
                userDao.deleteById(userLink.getId(), session.tenant_id);
            } catch (Exception e) {
                throw new BusinessException("Update Account Link failed (delete by id step)!");
            }
        } else {
            // Otherwise try deletion by pair
            if (userLink.getAccountId1() != null && userLink.getAccountId2() != null) {
                int a1 = Math.min(userLink.getAccountId1(), userLink.getAccountId2());
                int a2 = Math.max(userLink.getAccountId1(), userLink.getAccountId2());
                try {
                    userDao.deleteByPair(a1, a2, session.tenant_id);
                } catch (Exception e) {
                    throw new BusinessException("Update Account Link failed (delete by pair step)!");
                }
            } else {
                throw new BusinessException("Link ID or Account IDs required for update");
            }
        }

        userLink.setId(null);
        insertAccountLink(userLink);
    }

    @Override
    public void deleteAccountLinkByPair(int accountId1, int accountId2, int tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null)
            throw new BusinessException("Not logged in");
        if (tenantId != session.tenant_id)
            throw new BusinessException("Unauthorized tenant access");

        if (accountId1 > accountId2) {
            int temp = accountId1;
            accountId1 = accountId2;
            accountId2 = temp;
        }

        try {
            userDao.deleteByPair(accountId1, accountId2, session.tenant_id);
        } catch (Exception e) {
            throw new BusinessException("Delete Account Link by pair failed!");
        }
    }

    @Override
    public Map<String, Object> getLinkedAccounts(int accountId, int tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null)
            throw new BusinessException("Not logged in");
        if (tenantId != session.tenant_id)
            throw new BusinessException("Unauthorized tenant access");

        List<UserLink> links = userDao.findByAccountId(accountId, session.tenant_id);
        List<UserListDTO> accounts = new ArrayList<>();
        Map<Integer, String> linkTypesMap = new HashMap<>();

        for (UserLink link : links) {
            int linkedId = (link.getAccountId1() == accountId) ? link.getAccountId2() : link.getAccountId1();

            // Bidirectional is always included.
            // Unidirectional is included only if the current account is the source.
            if (link.getLinkType() == UserLink.LinkType.BIDIRECTIONAL ||
                    (link.getLinkType() == UserLink.LinkType.UNIDIRECTIONAL &&
                            link.getSourceAccountId() != null && link.getSourceAccountId() == accountId)) {

                UserListDTO linkedUser = userDao.findUserByIdAndTenantId(linkedId, session.tenant_id);
                if (linkedUser != null) {
                    accounts.add(linkedUser);
                    linkTypesMap.put(linkedId, link.getLinkType().name().toLowerCase());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("accounts", accounts);
        result.put("link_types_map", linkTypesMap);
        result.put("tenant_id", session.tenant_id);
        return result;
    }

    @Override
    public List<UserListDTO> getAllLinkedAccounts(int accountId, int tenantId) {
        Map<String, Object> linkedData = getLinkedAccounts(accountId, tenantId);
        @SuppressWarnings("unchecked")
        List<UserListDTO> accounts = (List<UserListDTO>) linkedData.get("accounts");

        // Include current account if not present
        boolean currentPresent = false;
        for (UserListDTO acc : accounts) {
            if (acc.getId() == accountId) {
                currentPresent = true;
                break;
            }
        }

        if (!currentPresent) {
            UserListDTO current = userDao.findUserByIdAndTenantId(accountId, tenantId);
            if (current != null) {
                accounts.add(0, current);
            }
        }

        return accounts;
    }
}
