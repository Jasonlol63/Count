package com.eazycount.service;

import com.eazycount.dto.MaintenanceBankProcessDTO;
import com.eazycount.dto.MaintenancePaymentDTO;
import com.eazycount.dto.MaintenanceTransactionDTO;

import java.util.List;

public interface MaintenanceService {

    List<MaintenanceTransactionDTO> findMaintenanceTransactionsRows(MaintenanceTransactionDTO mt);

    List<MaintenancePaymentDTO> findPaymentMaintenanceRows(MaintenancePaymentDTO request);

    List<MaintenanceBankProcessDTO> findBankProcessMaintenanceRows(MaintenanceBankProcessDTO request);

    // Soft-delete Payment Maintenance lines: archive to transactions_deleted
    void deletePaymentMaintenanceRows(MaintenancePaymentDTO request);

    // Soft-delete Bank Process Maintenance lines: archive to transactions_deleted
    void deleteBankProcessMaintenanceRows(MaintenanceBankProcessDTO request);
}
