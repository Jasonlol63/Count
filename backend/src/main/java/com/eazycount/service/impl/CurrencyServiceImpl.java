package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.entity.Currency;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.CurrencyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CurrencyServiceImpl implements CurrencyService {

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public List<Currency> findCurrencyByTenantId(Integer tenantId) {
        if(tenantId == null){
            throw new BusinessException("Invalid tenantId!");
        }
        return currencyDao.findCurrencyByTenantId(tenantId);
    }

    @Transactional
    @Override
    public Currency addNewCurrency(Currency currency) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }
        if (currency == null || currency.getTenantId() == null) {
            throw new BusinessException("Invalid tenant id");
        }

        String code = currency.getCode() != null ? currency.getCode().trim().toUpperCase() : "";
        if (code.isBlank()) {
            throw new BusinessException("Currency code is required");
        }

        currency.setCode(code);
        if (currency.getSyncSource() == null) {
            currency.setSyncSource(Currency.SourceType.MANUAL);
        }
        if (currency.getStatus() == null) {
            currency.setStatus(Currency.Status.ACTIVE);
        }

        if (currencyDao.findDuplicateByTenantIDAndCode(Integer.parseInt(currency.getTenantId()), code) > 0) {
            throw new BusinessException("Duplicate currency code");
        }

        try {
            currencyDao.addNewCurrency(currency);
        } catch (Exception e) {
            throw new BusinessException("Insert Currency Failed!");
        }

        return currency;
    }

    @Transactional
    @Override
    public void deleteCurrencyByIdAndTenantId (Integer id, Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        if (id == null || id <= 0 || tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid request");
        }

        Currency currency = currencyDao.findByIdAndTenantId(id, tenantId);
        if(currency == null){
            throw new BusinessException("Currency not found or access denied");
        }

        try{
            currencyDao.deleteCurrencyByIdAndTenantId(id, tenantId);
        }catch (Exception e){
            throw new BusinessException("Delete Currency Failed!");
        }
    }
}
