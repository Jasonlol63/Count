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

    /** Manual ADJUSTMENT Win/Loss aggregate (signed amount on To account only). */
    List<TransactionDTO.SearchAggregateRow> aggregateManualAdjustmentWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Manual PROFIT Win/Loss aggregate (From +amount, To −amount; no bank_process_posted_id).
     */
    List<TransactionDTO.SearchAggregateRow> aggregateManualProfitWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * RATE Middle-Man fee Win/Loss (From/middleman +, To/leg2-payer −; same rate_group, not leg1/leg2).
     */
    List<TransactionDTO.SearchAggregateRow> aggregateManualRateMiddlemanWinLoss(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Manual transfer Cr/Dr aggregate (PAYMENT / CLAIM / CLEAR / CONTRA; no bank_process_posted_id).
     * To ({@code account_id}) = −amount; From ({@code from_account_id}) = +amount.
     */
    List<TransactionDTO.SearchAggregateRow> aggregateDomainPaymentCrDr(
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

    List<TransactionDTO.HistoryBfAggregateRow> aggregateDomainPaymentBfByAccount(
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

    List<TransactionDTO.HistoryLineRow> findDomainPaymentHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryBfAggregateRow> aggregateManualAdjustmentBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryLineRow> findManualAdjustmentHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryBfAggregateRow> aggregateManualProfitBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryBfAggregateRow> aggregateManualRateMiddlemanBfByAccount(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("currencyCodes") List<String> currencyCodes);

    List<TransactionDTO.HistoryLineRow> findManualProfitHistoryLines(
            @Param("tenantId") Integer tenantId,
            @Param("accountId") Integer accountId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes);
}
