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
        List<BankProcessDTO> list = bankProcessDao.findAllBankProcess(tenantId);
        if (list == null || list.isEmpty()) {
            return list;
        }
        // Ensure root status string is always present for SPA normalize.
        for (BankProcessDTO dto : list) {
            if (dto == null) {
                continue;
            }
            if (dto.getStatus() == null || dto.getStatus().isBlank()) {
                BankProcess bp = dto.getBankProcess();
                if (bp != null && bp.getStatus() != null) {
                    dto.setStatus(bp.getStatus().name());
                }
            }
        }
        return list;
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

    @Override
    @Transactional
    public BankProcessDTO updateBankProcessDetails(BankProcessDTO bankProcessDTO) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (bankProcessDTO == null) {
            throw new BusinessException("Invalid request");
        }
        if (bankProcessDTO.getId() == null) {
            throw new BusinessException("Invalid bank process ID");
        }
        if (bankProcessDTO.getTenantId() == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        BankProcess updated = updateBankProcess(bankProcessDTO, sessionUser);
        deleteBankProcessShareBatch(updated.getId());
        List<BankProcessShare> shares = insertProfitSharing(updated.getId(), bankProcessDTO.getShares());

        bankProcessDTO.setId(updated.getId());
        bankProcessDTO.setCountryId(updated.getCountryId());
        bankProcessDTO.setBankOptionId(updated.getBankOptionId());
        bankProcessDTO.setCardOwner(updated.getCardOwner());
        bankProcessDTO.setCardOwnerType(updated.getCardOwnerType());
        bankProcessDTO.setFrequency(updated.getFrequency().name());
        bankProcessDTO.setShares(shares);
        return bankProcessDTO;
    }

    @Override
    @Transactional
    public void deleteBankProcess(Integer id, Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null || id <= 0) {
            throw new BusinessException("Invalid Bank Process ID!");
        }
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        BankProcess existing = bankProcessDao.findBKProcessByIdAndTenantId(id, tenantId);
        if (existing == null) {
            throw new BusinessException("Bank process not found!");
        }
        if (existing.getStatus() == null || existing.getStatus() != BankProcess.Status.INACTIVE) {
            throw new BusinessException("Bank process is not inactive, cannot be deleted!");
        }

        try {
            deleteBankProcessShareBatch(id);
            bankProcessDao.deleteBankProcess(id, tenantId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to delete bank process!");
        }
    }

    @Override
    @Transactional
    public BankProcess updateBankProcessStatus(Integer id, Integer tenantId, BankProcess.Status status) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null) {
            throw new BusinessException("Invalid Bank Process ID!");
        }
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        if (status == null) {
            throw new BusinessException("Status is required!");
        }
        if (status == BankProcess.Status.WAITING) {
            throw new BusinessException("Invalid status!");
        }

        BankProcess existing = bankProcessDao.findBKProcessByIdAndTenantId(id, tenantId);
        if (existing == null) {
            throw new BusinessException("Bank process not found!");
        }

        try {
            bankProcessDao.updateStatus(id, tenantId, status);
        } catch (Exception e) {
            throw new BusinessException("Update bank process status failed. Please try again!");
        }

        existing.setStatus(status);
        existing.setUpdatedBy(sessionUser.login_id);
        return existing;
    }

    @Override
    @Transactional
    public void updateBankProcessRemark(Integer id, Integer tenantId, String remark) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null || id <= 0) {
            throw new BusinessException("Invalid Bank Process ID!");
        }
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        BankProcess existing = bankProcessDao.findBKProcessByIdAndTenantId(id, tenantId);
        if (existing == null) {
            throw new BusinessException("Bank process not found!");
        }

        try {
            bankProcessDao.updateRemark(id, tenantId, remark, sessionUser.login_id);
        } catch (Exception e) {
            throw new BusinessException("Update bank process remark failed. Please try again!");
        }
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

        BankProcess.Frequency frequency = parseFrequency(bankProcessDTO.getFrequency());

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
        bankProcess.setDayEndMonthlyCapEnabled(resolveDayEndMonthlyCapEnabled(frequency, bankProcessDTO.getDayEndMonthlyCapEnabled()));
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
        bankProcess.setUpdatedAt(null);

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

    private BankProcess updateBankProcess(BankProcessDTO bankProcessDTO, SessionUser sessionUser) {
        BankProcess existing = bankProcessDao.findBKProcessByIdAndTenantId(
                bankProcessDTO.getId(), bankProcessDTO.getTenantId());
        if (existing == null) {
            throw new BusinessException("Bank process not found!");
        }

        BankProcess.Frequency frequency = parseFrequency(bankProcessDTO.getFrequency());

        BankProcess bankProcess = new BankProcess();
        bankProcess.setId(existing.getId());
        bankProcess.setTenantId(existing.getTenantId());
        bankProcess.setCountryId(existing.getCountryId());
        bankProcess.setBankOptionId(existing.getBankOptionId());
        bankProcess.setCardOwner(existing.getCardOwner());
        bankProcess.setCardOwnerType(existing.getCardOwnerType());
        bankProcess.setDayStart(bankProcessDTO.getDayStart());
        bankProcess.setDayEnd(bankProcessDTO.getDayEnd());
        bankProcess.setDayEndMonthlyCapEnabled(resolveDayEndMonthlyCapEnabled(frequency, bankProcessDTO.getDayEndMonthlyCapEnabled()));
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
        bankProcess.setUpdatedBy(sessionUser.login_id);

        try {
            bankProcessDao.updateBankProcess(bankProcess);
        } catch (Exception e) {
            throw new BusinessException("Update bank process failed. Please try again!");
        }
        return bankProcess;
    }

    private static BankProcess.Frequency parseFrequency(String raw) {
        try {
            String freq = raw != null ? raw.trim().toUpperCase() : "";
            return BankProcess.Frequency.valueOf(freq);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid frequency!");
        }
    }

    /** Only 1st-of-every-month may enable last-month DAY_END_TAIL; otherwise always false. */
    private static boolean resolveDayEndMonthlyCapEnabled(BankProcess.Frequency frequency, Boolean raw) {
        return frequency == BankProcess.Frequency.FIRST_OF_EVERY_MONTH && Boolean.TRUE.equals(raw);
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

    private void deleteBankProcessShareBatch(Integer bankProcessId) {
        if (bankProcessId == null || bankProcessId <= 0) {
            throw new BusinessException("Bank process ID is required!");
        }
        try {
            bankProcessDao.deleteBankProcessShareBatch(bankProcessId);
        } catch (Exception e) {
            throw new BusinessException("Delete bank process share failed. Please try again!");
        }
    }
}
