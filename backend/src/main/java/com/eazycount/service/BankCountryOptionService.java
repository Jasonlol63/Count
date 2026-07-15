package com.eazycount.service;

import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BankCountryOptionService {

    List<BankCountry> findAllCountry(Integer tenantId);

    List<BankOption> findAllBankInCountry(Integer tenantId, Integer countryId);

    void insertNewCountry(BankCountry bankCountry);

    void insertNewBankOption(BankOption bankOption);

    void deleteCountryByIdAndTenantId(Integer id,Integer tenantId);

    void deleteBankOptionByIdAndTenantId(Integer id, Integer tenantId, Integer countryId);

}
