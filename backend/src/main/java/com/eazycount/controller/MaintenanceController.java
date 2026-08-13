package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.*;
import com.eazycount.service.MaintenanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    @Autowired
    private MaintenanceService maintenanceService;

    @PostMapping("/transaction-maintenance/list")
    public ResponseEntity<Map<String, Object>> listTransactionMaintenance(@RequestBody MaintenanceTransactionDTO mt) {
        try {
            List<MaintenanceTransactionDTO> rows = maintenanceService.findMaintenanceTransactionsRows(mt);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Transaction maintenance list retrieved",
                    "data", rows));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/capture-maintenance/list")
    public ResponseEntity<Map<String, Object>> listCaptureMaintenance(@RequestBody MaintenanceCaptureDTO mc) {
        try {
            List<MaintenanceCaptureDTO> rows = maintenanceService.findMaintenanceCaptureRows(mc);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Capture maintenance list retrieved",
                    "data", rows));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/capture-maintenance/delete")
    public ResponseEntity<Map<String, Object>> deleteCaptureMaintenance(@RequestBody MaintenanceCaptureDTO mc) {
        try {
            maintenanceService.deleteMaintenanceCaptureRows(mc);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Capture deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/payment-maintenance/list")
    public ResponseEntity<Map<String, Object>> listPaymentMaintenance(@RequestBody MaintenancePaymentDTO request) {
        try {
            List<MaintenancePaymentDTO> rows =
                    maintenanceService.findPaymentMaintenanceRows(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Payment maintenance list retrieved",
                    "data", rows));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/payment-maintenance/delete")
    public ResponseEntity<Map<String, Object>> deletePaymentMaintenance(@RequestBody MaintenancePaymentDTO request) {
        try {
            maintenanceService.deletePaymentMaintenanceRows(request);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Payment deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/bankprocess-maintenance/list")
    public ResponseEntity<Map<String, Object>> listBankProcessMaintenance(@RequestBody MaintenanceBankProcessDTO request) {
        try {
            List<MaintenanceBankProcessDTO> rows =
                    maintenanceService.findBankProcessMaintenanceRows(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "BankProcess maintenance list retrieved",
                    "data", rows));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/bankprocess-maintenance/delete")
    public ResponseEntity<Map<String, Object>> deleteBankProcessMaintenance(@RequestBody MaintenanceBankProcessDTO request) {
        try {
            maintenanceService.deleteBankProcessMaintenanceRows(request);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "BankProcess deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/formula-maintenance/list")
    public ResponseEntity<Map<String, Object>> listFormulaMaintenance(@RequestBody MaintenanceFormulaDTO ft) {
        try {
            List<MaintenanceFormulaDTO> rows = maintenanceService.findMaintenanceFormulaRows(ft);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Formula maintenance list retrieved",
                    "data", rows));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/formula-maintenance/update")
    public ResponseEntity<Map<String, Object>> updateFormulaMaintenance(@RequestBody MaintenanceFormulaDTO ft) {
        try {
            maintenanceService.updateFormulaMaintenance(ft);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Formula maintenance updated successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/formula-maintenance/delete")
    public ResponseEntity<Map<String, Object>> deleteFormulaMaintenance(@RequestBody MaintenanceFormulaDTO ft) {
        try {
            maintenanceService.deleteFormulaMaintenance(ft);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Formula deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(BusinessException e) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
