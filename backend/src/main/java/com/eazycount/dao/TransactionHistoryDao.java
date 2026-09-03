package com.eazycount.dao;

import com.eazycount.dto.TransactionHistoryBfAggregateRow;
import com.eazycount.dto.TransactionHistoryLineRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

// Payment History drill-down (BF balance + line detail) for TransactionHistoryServiceImpl.
@Mapper
public interface TransactionHistoryDao {

    List<TransactionHistoryBfAggregateRow> aggregateBankProcessBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryBfAggregateRow> aggregateDataCaptureBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryBfAggregateRow> aggregateDomainPaymentBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("excludeFeeCommission") boolean excludeFeeCommission);

    List<TransactionHistoryLineRow> findBankProcessHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    //Counterpart to findBankProcessHistoryLines for Data Capture Summary.
    List<TransactionHistoryLineRow> findDataCaptureHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryLineRow> findDomainPaymentHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryBfAggregateRow> aggregateManualAdjustmentBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryLineRow> findManualAdjustmentHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryBfAggregateRow> aggregateManualProfitBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryBfAggregateRow> aggregateManualRateMiddlemanBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionHistoryLineRow> findManualProfitHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);
}
