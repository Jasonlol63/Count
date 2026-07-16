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

    BankProcess findBKProcessByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    BankProcessShare findProcessShare(@Param("bankProcessId") Integer bankProcessId);

    List<BankProcessShare> findSharesByBankProcessId(@Param("bankProcessId") Integer bankProcessId);

    void insertNewBankProcess(BankProcess bankProcess);

    void updateBankProcess(BankProcess bankProcess);

    void deleteBankProcess(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    void updateStatus(@Param("id") Integer id, @Param("tenantId") Integer tenantId, @Param("status") BankProcess.Status status);

    void updateRemark(@Param("id") Integer id,
                      @Param("tenantId") Integer tenantId,
                      @Param("remark") String remark,
                      @Param("updatedBy") String updatedBy);

    void insertNewBankProcessShareBatch(@Param("list") List<BankProcessShare> list);

    void deleteBankProcessShareBatch(@Param("bankProcessId") Integer bankProcessId);

}
