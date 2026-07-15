package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankCountryOptionDao;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.BankCountryOptionService;
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
        requireLogin();

        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant ID!");
        }

        return bankCountryOptionDao.findAllCountry(tenantId);
    }

    @Override
    public List<BankOption> findAllBankInCountry(Integer tenantId, Integer countryId) {
        requireLogin();

        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant ID!");
        }
        if (countryId == null) {
            throw new BusinessException("Country ID is required!");
        }

        BankCountry country = bankCountryOptionDao.findCountryById(tenantId, countryId);
        if (country == null) {
            throw new BusinessException("Country not found!");
        }

        return bankCountryOptionDao.findAllBankInCountry(tenantId, countryId);
    }

    @Transactional
    @Override
    public void insertNewCountry(BankCountry bankCountry) {
        requireLogin();
        if (bankCountry == null) {
            throw new BusinessException("Bank Country is required!");
        }
        if (bankCountry.getTenantId() == null) {
            throw new BusinessException("Invalid Tenant ID!");
        }

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
        requireLogin();

        if (bankOption == null) {
            throw new BusinessException("Bank Option is required!");
        }
        if (bankOption.getTenantId() == null) {
            throw new BusinessException("Invalid Tenant ID!");
        }
        if (bankOption.getCountryId() == null) {
            throw new BusinessException("Country ID is required!");
        }

        String name = bankOption.getName() != null ? bankOption.getName().trim().toUpperCase() : "";
        if (name.isBlank()) {
            throw new BusinessException("Bank Option name is required!");
        }

        BankCountry country = bankCountryOptionDao.findCountryById(bankOption.getTenantId(), bankOption.getCountryId());
        if (country == null) {
            throw new BusinessException("Country not found!");
        }

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
        requireLogin();

        if (id == null) {
            throw new BusinessException("Country ID is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("Tenant ID is required!");
        }

        BankCountry find = bankCountryOptionDao.findCountryById(tenantId, id);
        if (find == null) {
            throw new BusinessException("Country not found!");
        }

        try {
            bankCountryOptionDao.deleteCountryByIdAndTenantId(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete country! It may be in use by a bank process.");
        }
    }

    @Transactional
    @Override
    public void deleteBankOptionByIdAndTenantId(Integer id, Integer tenantId, Integer countryId) {
        requireLogin();

        if (id == null) {
            throw new BusinessException("Bank Option ID is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("Tenant ID is required!");
        }
        if (countryId == null) {
            throw new BusinessException("Country ID is required!");
        }

        BankOption find = bankCountryOptionDao.findBankOptionById(tenantId, countryId, id);
        if (find == null) {
            throw new BusinessException("Bank option not found!");
        }

        try {
            bankCountryOptionDao.deleteBankOptionByIdAndTenantId(id, tenantId, countryId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete bank option! It may be in use by a bank process.");
        }
    }

    private static void requireLogin() {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
    }
}
