package com.eazycount.service;

import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.UserLink;

import java.util.List;
import java.util.Map;


public interface UserService {
    List<UserListDTO> findUserByTenantId(Integer tenantId);

    UserListDTO createUser(UserListDTO userListDTO);

    UserListDTO updateUser(UserListDTO userListDTO);

    UserListDTO updateStatusByUserId(Integer id, Integer scopeTenantId);

    void deleteUserByIdAndStatus(Integer id, Integer scopeTenantId);

    void insertAccountLink(UserLink userLink);

    void deleteAccountLinkById(long id);

    void deleteAccountLinkByAccountId(int accountId, int tenantId);

    void deleteAccountLinkByPair(int accountId1, int accountId2, int tenantId);

    void updateAccountLink(UserLink userLink);

    Map<String, Object> getLinkedAccounts(int accountId, int tenantId);

    List<UserListDTO> getAllLinkedAccounts(int accountId, int tenantId);
}
