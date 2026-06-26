package com.eazycount.dao;

import com.eazycount.dto.AdminListDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.User;
import com.eazycount.entity.UserTenantAccess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserDao {

    List<UserListDTO> findUserByTenantId(int tenantId);

    UserListDTO findUserByAccIdAndTenantId(@Param("account_id") int accountId, @Param("tenantId") int tenantId);

    List<Integer> findTenantIdsByUserId(@Param("id") int id);

    void addUserDetails(User user);

    void insertAccountTenantAccess(UserTenantAccess userTenantAccess);

    void updateUserDetails(User user);

    void updateAccountTenantAccess(UserTenantAccess userTenantAccess);

    int deleteUserByIdAndStatus(@Param("id") int id, @Param("status") Admin.UserStatus status);

    void deleteUserTenantAccessByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

    void updateStatusByUserId(@Param("id") int id, @Param("status") Admin.UserStatus status);

}
