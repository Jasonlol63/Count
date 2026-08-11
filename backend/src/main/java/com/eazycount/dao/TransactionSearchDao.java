package com.eazycount.dao;

import com.eazycount.dto.TransactionSearchAggregateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Transaction Payment main list (Win/Loss + Cr/Dr account balances) — backs
 * {@code TransactionSearchServiceImpl} only. Split out of {@link TransactionDao} so this interface's
 * method set matches exactly what the Search page needs (see docs/transaction-datacapture-winloss.md).
 */
@Mapper
public interface TransactionSearchDao {

    List<TransactionSearchAggregateRow> aggregateBankProcessWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Data Capture Summary Win/Loss aggregate (submitted directly by
     * {@code DataCaptureSummaryServiceImpl}, no bank_process_posted_id — not from the Bank Process
     * posting flow). Same shape/signing as {@link #aggregateBankProcessWinLoss}.
     */
    List<TransactionSearchAggregateRow> aggregateDataCaptureWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /** Manual ADJUSTMENT Win/Loss aggregate (signed amount on To account only). */
    List<TransactionSearchAggregateRow> aggregateManualAdjustmentWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Manual PROFIT Win/Loss aggregate (From +amount, To −amount; no bank_process_posted_id).
     */
    List<TransactionSearchAggregateRow> aggregateManualProfitWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * RATE Middle-Man fee Win/Loss (From/middleman +, To/leg2-payer −; same rate_group, not leg1/leg2).
     */
    List<TransactionSearchAggregateRow> aggregateManualRateMiddlemanWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Manual transfer Cr/Dr aggregate (PAYMENT / CLAIM / CLEAR / CONTRA; no bank_process_posted_id).
     * To ({@code account_id}) = −amount; From ({@code from_account_id}) = +amount.
     */
    List<TransactionSearchAggregateRow> aggregateDomainPaymentCrDr(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Account × linked currency shells for tenant (optional role / currency filters).
     * Used by Show all 0 balance to add never-transacted rows.
     */
    List<TransactionSearchAggregateRow> findAccountCurrencyShells(
            @Param("tenantId") Integer tenantId,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);
}
