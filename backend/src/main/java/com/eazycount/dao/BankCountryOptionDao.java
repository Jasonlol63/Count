package com.eazycount.dao;

import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BankCountryOptionDao {

    // Find All Country and Bank Option for List
    List<BankCountry> findAllCountry(@Param("tenantId") Integer tenantId);

    List<BankOption> findAllBankInCountry(@Param("tenantId") Integer tenantId, @Param("countryId") Integer countryId);

    // Find Country and Bank Option by ID
    BankCountry findCountryById(@Param("tenantId") Integer tenantId,@Param("id") Integer id);

    BankOption findBankOptionById(@Param("tenantId") Integer tenantId,@Param("countryId") Integer countryId, @Param("id") Integer id);

    // Check Duplicate Use
    BankOption findBankOptionByName(@Param("tenantId") Integer tenantId,@Param("countryId") Integer countryId, @Param("name") String name);

    BankCountry findCountryByCode(@Param("tenantId") Integer tenantId, @Param("code") String code);

    // Insert/Delete Country and Bank Option
    void insertNewCountry(BankCountry bankCountry);

    void insertNewBankOption(BankOption bankOption);

    void deleteCountryByIdAndTenantId(@Param("id") Integer id,@Param("tenantId") Integer tenantId);

    void deleteBankOptionByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId, @Param("countryId") Integer countryId);
}
