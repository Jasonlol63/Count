package com.eazycount.service;

import com.eazycount.dto.MaintenanceBankProcessDTO;
import com.eazycount.dto.MaintenanceCaptureDTO;
import com.eazycount.dto.MaintenanceFormulaDTO;
import com.eazycount.dto.MaintenancePaymentDTO;
import com.eazycount.dto.MaintenanceTransactionDTO;
import com.eazycount.entity.DataCaptureFormula;

import java.util.List;

public interface MaintenanceService {

    List<MaintenanceTransactionDTO> findMaintenanceTransactionsRows(MaintenanceTransactionDTO mt);

    List<MaintenanceCaptureDTO> findMaintenanceCaptureRows(MaintenanceCaptureDTO mc);

    // Soft-delete Capture Maintenance: whole capture (all its lines) at a time; cascades into
    void deleteMaintenanceCaptureRows(MaintenanceCaptureDTO mc);

    List<MaintenanceFormulaDTO> findMaintenanceFormulaRows(MaintenanceFormulaDTO mf);

    void updateFormulaMaintenance(MaintenanceFormulaDTO ft);

    void deleteFormulaMaintenance(MaintenanceFormulaDTO ft);

    List<MaintenancePaymentDTO> findPaymentMaintenanceRows(MaintenancePaymentDTO request);

    List<MaintenanceBankProcessDTO> findBankProcessMaintenanceRows(MaintenanceBankProcessDTO request);

    // Soft-delete Payment Maintenance lines: archive to transactions_deleted.
    // Returns the ids actually deleted (a subset of request.transactionIds — resolveDeletableBatch
    // filters out ones that don't exist/aren't deletable), used by @Audited's entityIdExpr so the
    // audit log reflects what really happened, not what was merely requested.
    List<Integer> deletePaymentMaintenanceRows(MaintenancePaymentDTO request);

    // Soft-delete Bank Process Maintenance lines: archive to transactions_deleted.
    // Returns the ids actually deleted, same reasoning as deletePaymentMaintenanceRows above.
    List<Integer> deleteBankProcessMaintenanceRows(MaintenanceBankProcessDTO request);
}
