package com.eazycount.service;

import com.eazycount.dto.UserListDTO;

import java.util.List;


public interface UserService {
    List<UserListDTO> findUserByTenantId(Integer tenantId);

    UserListDTO createUser(UserListDTO userListDTO);

    UserListDTO updateUser(UserListDTO userListDTO);

    UserListDTO updateStatusByUserId(Integer id, Integer scopeTenantId);

    void deleteUserByIdAndStatus(Integer id, Integer scopeTenantId);
}
