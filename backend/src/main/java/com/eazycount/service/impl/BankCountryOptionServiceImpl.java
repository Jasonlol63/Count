package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankCountryOptionDao;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.service.BankCountryOptionService;
import com.eazycount.util.AccessControlUtils;
import com.eazycount.util.AssertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BankCountryOptionServiceImpl implements BankCountryOptionService {

    @Autowired
    private BankCountryOptionDao bankCountryOptionDao;

    @Override
    public List<BankCountry> findAllCountry(Integer tenantId) {
        AccessControlUtils.requireLoggedIn();

        AccessControlUtils.requireValidTenantId(tenantId);

        return bankCountryOptionDao.findAllCountry(tenantId);
    }

    @Override
    public List<BankOption> findAllBankInCountry(Integer tenantId, Integer countryId) {
        AccessControlUtils.requireLoggedIn();

        AccessControlUtils.requireValidTenantId(tenantId);
        if (countryId == null) {
            throw new BusinessException("Country ID is required!");
        }

        AssertUtils.requireFound(bankCountryOptionDao.findCountryById(tenantId, countryId), "Country not found!");

        return bankCountryOptionDao.findAllBankInCountry(tenantId, countryId);
    }

    @Transactional
    @Override
    public void insertNewCountry(BankCountry bankCountry) {
        AccessControlUtils.requireWritable(AccessControlUtils.requireLoggedIn());
        AccessControlUtils.requireValidTenantId(bankCountry != null ? bankCountry.getTenantId() : null);

        String code = bankCountry.getCode() != null ? bankCountry.getCode().trim().toUpperCase() : "";
        if (code.isBlank()) {
            throw new BusinessException("Country code is required!");
        }

        BankCountry match = bankCountryOptionDao.findCountryByCode(bankCountry.getTenantId(), code);
        if (match != null) {
            throw new BusinessException("Country Name already exists!");
        }

        bankCountry.setCode(code);

        try {
            bankCountryOptionDao.insertNewCountry(bankCountry);
        } catch (Exception e) {
            throw new BusinessException("Failed to insert new country!");
        }
    }

    @Transactional
    @Override
    public void insertNewBankOption(BankOption bankOption) {
        AccessControlUtils.requireWritable(AccessControlUtils.requireLoggedIn());

        AccessControlUtils.requireValidTenantId(bankOption != null ? bankOption.getTenantId() : null);
        if (bankOption.getCountryId() == null) {
            throw new BusinessException("Country ID is required!");
        }

        String name = bankOption.getName() != null ? bankOption.getName().trim().toUpperCase() : "";
        if (name.isBlank()) {
            throw new BusinessException("Bank Option name is required!");
        }

        AssertUtils.requireFound(
                bankCountryOptionDao.findCountryById(bankOption.getTenantId(), bankOption.getCountryId()), "Country not found!");

        BankOption match = bankCountryOptionDao.findBankOptionByName(
                bankOption.getTenantId(), bankOption.getCountryId(), name);
        if (match != null) {
            throw new BusinessException("Bank Option Name already exists!");
        }

        bankOption.setName(name);

        try {
            bankCountryOptionDao.insertNewBankOption(bankOption);
        } catch (Exception e) {
            throw new BusinessException("Failed to insert new bank option!");
        }
    }

    @Transactional
    @Override
    public void deleteCountryByIdAndTenantId(Integer id, Integer tenantId) {
        AccessControlUtils.requireWritable(AccessControlUtils.requireLoggedIn());

        if (id == null) {
            throw new BusinessException("Country ID is required!");
        }
        AccessControlUtils.requireValidTenantId(tenantId);

        AssertUtils.requireFound(bankCountryOptionDao.findCountryById(tenantId, id), "Country not found!");

        try {
            bankCountryOptionDao.deleteCountryByIdAndTenantId(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete country! It may be in use by a bank process.");
        }
    }

    @Transactional
    @Override
    public void deleteBankOptionByIdAndTenantId(Integer id, Integer tenantId, Integer countryId) {
        AccessControlUtils.requireWritable(AccessControlUtils.requireLoggedIn());

        if (id == null) {
            throw new BusinessException("Bank Option ID is required!");
        }
        AccessControlUtils.requireValidTenantId(tenantId);
        if (countryId == null) {
            throw new BusinessException("Country ID is required!");
        }

        AssertUtils.requireFound(bankCountryOptionDao.findBankOptionById(tenantId, countryId, id), "Bank option not found!");

        try {
            bankCountryOptionDao.deleteBankOptionByIdAndTenantId(id, tenantId, countryId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete bank option! It may be in use by a bank process.");
        }
    }

}
