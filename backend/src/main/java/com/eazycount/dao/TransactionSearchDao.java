package com.eazycount.dao;

import com.eazycount.dto.TransactionSearchAggregateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/* Transaction Payment list (Win/Loss + Cr/Dr) for TransactionSearchServiceImpl. */
@Mapper
public interface TransactionSearchDao {

    List<TransactionSearchAggregateRow> aggregateBankProcessWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    // Data Capture Summary Win/Loss aggregate (direct submit, no bank_process_posted_id);
    // same shape as aggregateBankProcessWinLoss.
    List<TransactionSearchAggregateRow> aggregateDataCaptureWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    // Manual ADJUSTMENT Win/Loss aggregate (signed amount on To account only).
    List<TransactionSearchAggregateRow> aggregateManualAdjustmentWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    // Manual PROFIT Win/Loss aggregate (From +amount, To −amount).
    List<TransactionSearchAggregateRow> aggregateManualProfitWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    //RATE Middle-Man Win/Loss: middleman's own +amount only; leg2 −amount half is in aggregateManualRateMiddlemanCrDr
    List<TransactionSearchAggregateRow> aggregateManualRateMiddlemanWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    //RATE Middle-Man Cr/Dr: leg2's −amount half (Rate-Mul + Service Fee), merged into leg2's own Cr/Dr.
    List<TransactionSearchAggregateRow> aggregateManualRateMiddlemanCrDr(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    //Manual transfer Cr/Dr aggregate (PAYMENT/CLAIM/CLEAR/CONTRA); To = −amount, From = +amount.
    List<TransactionSearchAggregateRow> aggregateDomainPaymentCrDr(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    // Account × currency shells for tenant, used by "Show all 0 balance" to include never-transacted rows.
    List<TransactionSearchAggregateRow> findAccountCurrencyShells(
            @Param("tenantId") Integer tenantId,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);
}
