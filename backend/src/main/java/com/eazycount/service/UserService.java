package com.eazycount.service;

import com.eazycount.dto.AdminRequest;
import com.eazycount.dto.UserListDTO;

import java.util.List;


public interface UserService {
    List<UserListDTO> findUserByTenantId(Integer tenantId);

    AdminRequest getUserDetails(Integer id, Integer scopeTenantId);

    UserListDTO createUser(UserRequest userRequest);

    UserListDTO updateUser(UserRequest userRequest);

    UserListDTO updateStatusByUserId(Integer id, Integer scopeTenantId);

    void deleteUserByIdAndStatus(Integer id, Integer scopeTenantId);
}
