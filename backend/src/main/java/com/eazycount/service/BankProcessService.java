package com.eazycount.service;

import com.eazycount.dto.BankProcessDTO;

import java.util.List;

public interface BankProcessService {

    List<BankProcessDTO> findAllBankProcess(Integer tenantId);

    BankProcessDTO insertBankProcess(BankProcessDTO bankProcessDTO);
}
