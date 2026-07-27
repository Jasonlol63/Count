package com.eazycount.service;

import com.eazycount.dto.TransactionDTO;

import java.util.List;

public interface MaintenanceService {

    List<TransactionDTO.PaymentMaintenanceRow> findPaymentMaintenanceRows(
            TransactionDTO.PaymentMaintenanceRequest request);

    List<TransactionDTO.BankProcessMaintenanceRow> findBankProcessMaintenanceRows(
            TransactionDTO.BankProcessMaintenanceRequest request);

    // Soft-delete Payment Maintenance lines: archive to transactions_deleted
    void deletePaymentMaintenanceRows(TransactionDTO.PaymentMaintenanceDeleteRequest request);

    // Soft-delete Bank Process Maintenance lines: archive to transactions_deleted
    void deleteBankProcessMaintenanceRows(TransactionDTO.BankProcessMaintenanceDeleteRequest request);
}
