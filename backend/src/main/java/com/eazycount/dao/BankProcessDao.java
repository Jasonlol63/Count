package com.eazycount.dao;

import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BankProcessShare;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BankProcessDao {

    List<BankProcessDTO> findAllBankProcess(@Param("tenantId") Integer tenantId);

    List<BankProcessShare> findProcessShare(@Param("bankProcessId") Integer bankProcessId);

    void insertNewBankProcess(BankProcess bankProcess);

    void insertNewBankProcessShare(BankProcessShare bankProcessShare);

    void insertNewBankProcessShareBatch(@Param("list") List<BankProcessShare> list);
}
