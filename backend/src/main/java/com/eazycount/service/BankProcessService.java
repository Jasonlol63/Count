package com.eazycount.service;

import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;

import java.util.List;

public interface BankProcessService {

    List<BankProcessDTO> findAllBankProcess(Integer tenantId);

    BankProcessDTO insertBankProcess(BankProcessDTO bankProcessDTO);

    BankProcessDTO updateBankProcessDetails(BankProcessDTO bankProcessDTO);

    void deleteBankProcess(Integer id, Integer tenantId);

    BankProcess updateBankProcessStatus(Integer id, Integer tenantId, BankProcess.Status status);

    void updateBankProcessRemark(Integer id, Integer tenantId, String remark);
}
