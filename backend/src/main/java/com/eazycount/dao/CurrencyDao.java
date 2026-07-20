package com.eazycount.dao;

import com.eazycount.dto.UserLinkedDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.UserCurrency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CurrencyDao {

    //List, Create, Delete Currency
    List<Currency> findCurrencyByTenantId(Integer tenantId);

    Currency findByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

    Currency findByTenantIdAndCode(@Param("tenantId") int tenantId, @Param("code") String code);

    int findDuplicateByTenantIDAndCode(@Param("tenantId") int tenantId, @Param("code") String code);

    void addNewCurrency(Currency currency);

    void deleteCurrencyByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

    //Save, Update Account and Currency at a time
    void insertAccountCurrency(UserCurrency row);

    void deleteByAccountIdAndTenantId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    List<Integer> findCurrencyIdsByAccountIdAndTenantId(@Param("accountId")int accountId, @Param("tenantId") int tenantId);

    int countValidCurrenciesForTenant( @Param("tenantId") int tenantId, @Param("currencyIds") List<Integer> currencyIds);

    //List, Update Linked Account Currency
    List<UserLinkedDTO> findLinkedAccountsByCurrencyIdAndTenantId(@Param("currencyId") int currencyId, @Param("tenantId") int tenantId);

    int countAccountCurrencyLink(@Param("accountId") int accountId, @Param("tenantId") int tenantId, @Param("currencyId") int currencyId);

    Integer maxSortOrderByAccountIdAndTenantId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    void deleteByAccountIdAndTenantIdAndCurrencyId(@Param("accountId") int accountId, @Param("tenantId") int tenantId, @Param("currencyId") int currencyId);

    int countByAccountIdAndTenantId(@Param("accountId") int accountId, @Param("tenantId") int tenantId);

    int countAccountsInTenant(@Param("tenantId") int tenantId, @Param("accountIds") List<Integer> accountIds);

}
