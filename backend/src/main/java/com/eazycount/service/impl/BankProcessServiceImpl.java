package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankCountryOptionDao;
import com.eazycount.dao.BankProcessDao;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BankProcessShare;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.BankProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class BankProcessServiceImpl implements BankProcessService {

    @Autowired
    private BankProcessDao bankProcessDao;

    @Autowired
    private BankCountryOptionDao bankCountryOptionDao;

    @Override
    public List<BankProcessDTO> findAllBankProcess(Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        return bankProcessDao.findAllBankProcess(tenantId);
    }

    @Override
    @Transactional
    public BankProcessDTO insertBankProcess(BankProcessDTO bankProcessDTO) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        BankProcess bankProcess = insertNewBankProcess(bankProcessDTO, sessionUser);
        List<BankProcessShare> shares = insertProfitSharing(bankProcess.getId(), bankProcessDTO.getShares());

        bankProcessDTO.setId(bankProcess.getId());
        bankProcessDTO.setCardOwner(bankProcess.getCardOwner());
        bankProcessDTO.setCardOwnerType(bankProcess.getCardOwnerType());
        bankProcessDTO.setFrequency(bankProcess.getFrequency().name());
        bankProcessDTO.setShares(shares);
        return bankProcessDTO;
    }

    private BankProcess insertNewBankProcess(BankProcessDTO bankProcessDTO, SessionUser sessionUser) {
        if (bankProcessDTO == null) {
            throw new BusinessException("Request body is required!");
        }
        if (bankProcessDTO.getTenantId() == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        if (bankProcessDTO.getCountryId() == null) {
            throw new BusinessException("Country ID is required!");
        }
        if (bankProcessDTO.getBankOptionId() == null) {
            throw new BusinessException("Bank option ID is required!");
        }

        String cardOwner = bankProcessDTO.getCardOwner() != null
                ? bankProcessDTO.getCardOwner().trim() : "";
        if (cardOwner.isEmpty()) {
            throw new BusinessException("Card owner is required!");
        }
        String cardOwnerType = bankProcessDTO.getCardOwnerType() != null
                ? bankProcessDTO.getCardOwnerType().trim() : "";
        if (cardOwnerType.isEmpty()) {
            throw new BusinessException("Card owner type is required!");
        }

        BankProcess.Frequency frequency;
        try {
            String freq = bankProcessDTO.getFrequency() != null
                    ? bankProcessDTO.getFrequency().trim().toUpperCase() : "";
            frequency = BankProcess.Frequency.valueOf(freq);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid frequency!");
        }

        BankCountry country = bankCountryOptionDao.findCountryById(
                bankProcessDTO.getTenantId(), bankProcessDTO.getCountryId());
        if (country == null) {
            throw new BusinessException("Country not found!");
        }

        BankOption bankOption = bankCountryOptionDao.findBankOptionById(
                bankProcessDTO.getTenantId(),
                bankProcessDTO.getCountryId(),
                bankProcessDTO.getBankOptionId());
        if (bankOption == null) {
            throw new BusinessException("Bank option not found!");
        }

        BankProcess bankProcess = new BankProcess();
        bankProcess.setTenantId(bankProcessDTO.getTenantId());
        bankProcess.setCountryId(bankProcessDTO.getCountryId());
        bankProcess.setBankOptionId(bankProcessDTO.getBankOptionId());
        bankProcess.setCardOwner(cardOwner);
        bankProcess.setCardOwnerType(cardOwnerType);
        bankProcess.setDayStart(bankProcessDTO.getDayStart());
        bankProcess.setDayEnd(bankProcessDTO.getDayEnd());
        bankProcess.setFrequency(frequency);
        bankProcess.setSupplierAccountId(bankProcessDTO.getSupplierAccountId());
        bankProcess.setSupplierPrice(bankProcessDTO.getSupplierPrice());
        bankProcess.setCustomerAccountId(bankProcessDTO.getCustomerAccountId());
        bankProcess.setCustomerPrice(bankProcessDTO.getCustomerPrice());
        bankProcess.setCompanyAccountId(bankProcessDTO.getCompanyAccountId());
        bankProcess.setCompanyPrice(bankProcessDTO.getCompanyPrice());
        bankProcess.setContract(bankProcessDTO.getContract());
        bankProcess.setInsurancePrice(bankProcessDTO.getInsurancePrice());
        bankProcess.setSop(bankProcessDTO.getSop());
        bankProcess.setRemark(bankProcessDTO.getRemark());
        bankProcess.setStatus(BankProcess.Status.ACTIVE);
        bankProcess.setCreatedBy(sessionUser.login_id);

        try {
            bankProcessDao.insertNewBankProcess(bankProcess);
        } catch (Exception e) {
            throw new BusinessException("Insert bank process failed. Please try again!");
        }
        if (bankProcess.getId() == null) {
            throw new BusinessException("Insert bank process failed. Please try again!");
        }
        return bankProcess;
    }

    private List<BankProcessShare> insertProfitSharing(Integer bankProcessId, List<BankProcessShare> shares) {
        if (bankProcessId == null) {
            throw new BusinessException("Bank process ID is required!");
        }
        if (shares == null || shares.isEmpty()) {
            return List.of();
        }

        List<BankProcessShare> toInsert = new ArrayList<>();
        Set<Integer> seenAccounts = new LinkedHashSet<>();
        int sortOrder = 0;

        for (BankProcessShare share : shares) {
            if (share == null) {
                continue;
            }
            Integer accountId = share.getAccountId();
            if (accountId == null || accountId <= 0 || !seenAccounts.add(accountId)) {
                continue;
            }
            BigDecimal amount = share.getAmount() != null ? share.getAmount() : BigDecimal.ZERO;

            BankProcessShare row = new BankProcessShare();
            row.setBankProcessId(bankProcessId);
            row.setAccountId(accountId);
            row.setAmount(amount);
            row.setSortOrder(share.getSortOrder() != null ? share.getSortOrder() : sortOrder);
            toInsert.add(row);
            sortOrder++;
        }

        if (toInsert.isEmpty()) {
            return List.of();
        }

        try {
            bankProcessDao.insertNewBankProcessShareBatch(toInsert);
        } catch (Exception e) {
            throw new BusinessException("Insert bank process share failed. Please try again!");
        }
        return toInsert;
    }
}
