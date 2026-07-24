package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

import java.util.List;

public interface MaintenanceService {

    List<TransactionDTO.PaymentMaintenanceRow> findPaymentMaintenanceRows(
            TransactionDTO.PaymentMaintenanceRequest request);

    List<TransactionDTO.BankProcessMaintenanceRow> findBankProcessMaintenanceRows(
            TransactionDTO.BankProcessMaintenanceRequest request);

    /**
     * Soft-delete Payment Maintenance lines: archive to {@code transactions_deleted},
     * then remove from {@code transactions}. List can still show archived rows.
     */
    TransactionDTO.PaymentMaintenanceDeleteResult deletePaymentMaintenanceRows(
            TransactionDTO.PaymentMaintenanceDeleteRequest request);

    /**
     * Soft-delete Bank Process Maintenance lines: archive to {@code transactions_deleted},
     * then remove from {@code transactions}. List can still show archived rows.
     */
    TransactionDTO.PaymentMaintenanceDeleteResult deleteBankProcessMaintenanceRows(
            TransactionDTO.BankProcessMaintenanceDeleteRequest request);
}
