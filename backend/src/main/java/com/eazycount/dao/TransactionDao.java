package com.eazycount.dao;

import com.eazycount.dto.TransactionDTO;
import com.eazycount.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TransactionDao {

    void insert(Transaction row);

    List<TransactionDTO.SearchAggregateRow> aggregateBankProcessWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    List<TransactionDTO.HistoryBfAggregateRow> aggregateBankProcessBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryLineRow> findBankProcessHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);
}
