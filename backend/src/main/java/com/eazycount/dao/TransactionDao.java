package com.eazycount.dao;

import com.eazycount.dto.TransactionDTO;
import com.eazycount.entity.Transaction;
import com.eazycount.entity.TransactionDeleted;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TransactionDao {

    void insert(Transaction row);

    List<Transaction> findByIdsAndTenantId(@Param("tenantId") Integer tenantId, @Param("ids") List<Integer> ids);

    void deleteByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int deleteByIdsAndTenantId(@Param("tenantId") Integer tenantId, @Param("ids") List<Integer> ids);

    void insertTransactionDeleted(TransactionDeleted transactionDeleted);

    /**
     * Archive live payment-maintenance lines into {@code transactions_deleted}
     * ({@code bank_process_posted_id IS NULL} only), then caller deletes from {@code transactions}.
     */
    int archivePaymentMaintenanceToDeleted(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids,
            @Param("deletedBy") String deletedBy);

    /**
     * Archive live bank-process-maintenance lines into {@code transactions_deleted}
     * ({@code bank_process_posted_id IS NOT NULL}, posted WIN/LOSE only).
     */
    int archiveBankProcessMaintenanceToDeleted(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids,
            @Param("deletedBy") String deletedBy);

    /**
     * All deletable Bank Process Maintenance transaction ids sharing any of the given posted ids.
     */
    List<Integer> findBankProcessMaintenanceIdsByPostedIds(
            @Param("tenantId") Integer tenantId,
            @Param("postedIds") List<Integer> postedIds);

    /**
     * Distinct {@code bank_process.id} for live maintenance transaction ids being deleted.
     */
    List<Integer> findBankProcessIdsByTransactionIds(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids);

    /**
     * All deletable Payment Maintenance transaction ids sharing any of the given RATE group ids.
     */
    List<Integer> findPaymentMaintenanceIdsByRateGroupIds(
            @Param("tenantId") Integer tenantId,
            @Param("rateGroupIds") List<String> rateGroupIds);

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

    /**
     * Account × linked currency shells for tenant (optional role / currency filters).
     * Used by Show all 0 balance to add never-transacted rows.
     */
    List<TransactionDTO.SearchAggregateRow> findAccountCurrencyShells(
            @Param("tenantId") Integer tenantId,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("categories") List<String> categories);

    /**
     * Payment Maintenance live bill lines: APPROVED manual / Domain / Renew
     * ({@code bank_process_posted_id IS NULL}).
     */
    List<TransactionDTO.PaymentMaintenanceRow> findPaymentMaintenanceRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("transactionType") String transactionType,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    /**
     * Payment Maintenance archived (soft-deleted) bill lines from {@code transactions_deleted}
     * ({@code bank_process_posted_id IS NULL}).
     */
    List<TransactionDTO.PaymentMaintenanceRow> findPaymentMaintenanceDeletedRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("transactionType") String transactionType,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    /**
     * Bank Process Maintenance live lines ({@code bank_process_posted_id IS NOT NULL}, WIN/LOSE).
     */
    List<TransactionDTO.BankProcessMaintenanceRow> findBankProcessMaintenanceRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    /**
     * Bank Process Maintenance archived lines from {@code transactions_deleted}.
     */
    List<TransactionDTO.BankProcessMaintenanceRow> findBankProcessMaintenanceDeletedRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);
}
