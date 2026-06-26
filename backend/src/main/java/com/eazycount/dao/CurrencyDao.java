package com.eazycount.dao;

import com.eazycount.entity.Currency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CurrencyDao {

    List<Currency> findCurrencyByTenantId(Integer tenantId);

    Currency findByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

    int findDuplicateByTenantIDAndCode(@Param("tenantId") int tenantId, @Param("code") String code);

    void addNewCurrency(Currency currency);

    void deleteCurrencyByIdAndTenantId(@Param("id") int id, @Param("tenantId") int tenantId);

}
