package com.eazycount.service;

import com.eazycount.entity.Currency;

import java.util.List;

public interface CurrencyService {

    List<Currency> findCurrencyByTenantId(Integer tenantId);

    Currency addNewCurrency(Currency currency);

    void deleteCurrencyByIdAndTenantId(Integer id, Integer tenantId);
}
