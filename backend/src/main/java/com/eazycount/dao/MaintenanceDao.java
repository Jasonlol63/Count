package com.eazycount.dao;

import com.eazycount.dto.MaintenanceBankProcessDTO;
import com.eazycount.dto.MaintenancePaymentDTO;
import com.eazycount.dto.MaintenanceTransactionDTO;
import com.eazycount.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** Payment Maintenance + Bank Process Maintenance (list/archive/delete); backs {@code MaintenanceServiceImpl}. */
@Mapper
public interface MaintenanceDao {

    List<Transaction> findByIdsAndTenantId(@Param("tenantId") Integer tenantId, @Param("ids") List<Integer> ids);

    int deleteByIdsAndTenantId(@Param("tenantId") Integer tenantId, @Param("ids") List<Integer> ids);

    // Archive live payment-maintenance lines into transactions_deleted; caller then deletes from transactions.
    int archivePaymentMaintenanceToDeleted(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids,
            @Param("deletedBy") String deletedBy);

    // Archive live posted WIN/LOSE bank-process-maintenance lines into transactions_deleted.
    int archiveBankProcessMaintenanceToDeleted(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids,
            @Param("deletedBy") String deletedBy);

    // Deletable Bank Process Maintenance ids sharing any of the given posted ids.
    List<Integer> findBankProcessMaintenanceIdsByPostedIds(
            @Param("tenantId") Integer tenantId,
            @Param("postedIds") List<Integer> postedIds);

    // Distinct bank_process.id for live maintenance transaction ids being deleted.
    List<Integer> findBankProcessIdsByTransactionIds(
            @Param("tenantId") Integer tenantId,
            @Param("ids") List<Integer> ids);

    // Deletable Payment Maintenance ids sharing any of the given RATE group ids.
    List<Integer> findPaymentMaintenanceIdsByRateGroupIds(
            @Param("tenantId") Integer tenantId,
            @Param("rateGroupIds") List<String> rateGroupIds);

    // Payment Maintenance live bill lines (manual/Domain/Renew).
    List<MaintenancePaymentDTO> findPaymentMaintenanceRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("transactionType") String transactionType,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    // Payment Maintenance archived (soft-deleted) bill lines.
    List<MaintenancePaymentDTO> findPaymentMaintenanceDeletedRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("transactionType") String transactionType,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    // Bank Process Maintenance live lines (posted WIN/LOSE).
    List<MaintenanceBankProcessDTO> findBankProcessMaintenanceRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    // Bank Process Maintenance archived lines.
    List<MaintenanceBankProcessDTO> findBankProcessMaintenanceDeletedRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("q") String q);

    // Transaction Maintenance (view-only): every data_capture_line row, MAIN+SUB, one category (GAME/BANK) at a time.
    List<MaintenanceTransactionDTO> findTransactionLineMaintenanceRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("process") String process,
            @Param("category") String category,
            @Param("q") String q);
}
