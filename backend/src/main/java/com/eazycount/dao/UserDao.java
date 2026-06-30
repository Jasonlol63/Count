package com.eazycount.dao;

import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.UserLink;
import com.eazycount.entity.User;
import com.eazycount.entity.UserTenantAccess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserDao {

    List<UserListDTO> findUserByTenantId(int tenantId);

    UserListDTO findUserByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

    List<Integer> findTenantIdsByUserId(@Param("id") int id);

    void addUserDetails(User user);

    void insertAccountTenantAccess(UserTenantAccess userTenantAccess);

    void updateUserDetails(User user);

    void updateAccountTenantAccess(UserTenantAccess userTenantAccess);

    void deleteUserByIdAndStatus(@Param("id") int id, @Param("status") User.AccountStatus status);

    void deleteUserTenantAccessByAccountIdAndTenantId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    void updateStatusByUserId(@Param("id") int id, @Param("status") User.AccountStatus status);

    /*Account Link Table*/
    List<UserLink> findByAccountId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    List<UserLink> findByPair(@Param("accountId1") int accountId1, @Param("accountId2") int accountId2, @Param("tenantId") int tenantId);

    void insertAccountLink(UserLink userLink);

    void deleteById(@Param("id") long id, @Param("tenantId") int tenantId);

    void deleteByAccountId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    void deleteByPair(@Param("accountId1") int accountId1, @Param("accountId2") int accountId2, @Param("tenantId") int tenantId);

}
