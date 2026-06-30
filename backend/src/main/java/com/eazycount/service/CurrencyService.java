package com.eazycount.service;

import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.dto.UserLinkedDTO;
import com.eazycount.entity.Currency;  
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CurrencyService {

    List<Currency> findCurrencyByTenantId(Integer tenantId);

    Currency addNewCurrency(Currency currency);

    void deleteCurrencyByIdAndTenantId(Integer id, Integer tenantId);

    void insertAccountCurrency(int accountId, int tenantId, List<Integer> currencyIds);

    void deleteByAccountIdAndTenantId(@Param("accountId") Integer accountId, @Param("tenantId") Integer tenantId);

    List<Integer> findCurrencyIdsByAccountIdAndTenantId(@Param("accountId")Integer accountId, @Param("tenantId") Integer tenantId);

    List<UserCurrencyDTO> findAvailableCurrencies(Integer tenantId, Integer accountId);

    UserLinkedDTO findLinkedAccountsByCurrencyIdAndTenantId(Integer currencyId, Integer tenantId);

    void bulkUpdateAccountCurrency(UserLinkedDTO request);
}
