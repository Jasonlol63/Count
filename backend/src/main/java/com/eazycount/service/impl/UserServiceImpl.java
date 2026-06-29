package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.UserListDTO;
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

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UserServiceImpl implements UserService {

    private static final Set<String> ALLOWED_ACCOUNT_LEDGER_ROLES = Set.of(
            "CAPITAL", "BANK", "CASH", "PROFIT", "EXPENSES", "COMPANY",
            "PARTNER", "STAFF", "SUPPLIER", "UPLINE", "AGENT", "MEMBER", "DEBTOR"
    );

    @Autowired
    private UserDao userDao;

    @Autowired
    private CurrencyService currencyService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public List<UserListDTO> findUserByTenantId(Integer tenantId){
        if(tenantId == null){
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
        if(userListDTO.getStatus() == null){
            user.setStatus(User.AccountStatus.ACTIVE);
        }

        if (user.getPaymentAlert() == null) {
            user.setPaymentAlert(0);
        }

        try{
            userDao.addUserDetails(user);
        }catch (Exception e){
            throw new BusinessException("Create user failed!");
        }

        if (user.getId() == null || user.getId() <= 0) {
            throw new BusinessException("Create user failed!");
        }

        UserTenantAccess userTenantAccess = new UserTenantAccess();
        userTenantAccess.setAccountId(user.getId());
        userTenantAccess.setTenantId(userListDTO.getScopeTenantId());

        try{
            userDao.insertAccountTenantAccess(userTenantAccess);
        } catch (Exception e) {
            throw new BusinessException("Create user tenant access failed!");
        }

        currencyService.insertAccountCurrency(
                user.getId(),
                tenantId,
                userListDTO.getCurrencyIds()
        );

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
        if(existing == null){
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

        try{
            UserTenantAccess userTenantAccess = new UserTenantAccess();
            userTenantAccess.setAccountId(userListDTO.getId());
            userTenantAccess.setTenantId(userListDTO.getScopeTenantId());
            userDao.updateAccountTenantAccess(userTenantAccess);
        }catch (Exception e){
            throw new BusinessException("Update UserTenantAccess failed!");
        }
        UserListDTO updated = userDao.findUserByIdAndTenantId(userListDTO.getId(), userListDTO.getScopeTenantId());
        if (updated == null) {
            throw new BusinessException("User not found after update!");
        }

        currencyService.deleteByAccountIdAndTenantId(
                userListDTO.getId(),
                userListDTO.getScopeTenantId()
        );
        currencyService.insertAccountCurrency(
                userListDTO.getId(),
                userListDTO.getScopeTenantId(),
                userListDTO.getCurrencyIds()
        );

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

        try{
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
            throw new com.eazycount.common.BusinessException("Status updated, but user is no longer visible in this tenant");
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

        try{
            userDao.deleteUserTenantAccessByAccountIdAndTenantId(id, scopeTenantId);
        }catch (Exception e){
            throw new BusinessException("Delete UserTenantAccess failed!");
        }

        try{
            userDao.deleteUserByIdAndStatus(id, User.AccountStatus.INACTIVE);
        }catch (Exception e){
            throw new BusinessException("Delete User failed!");
        }
    }
}
