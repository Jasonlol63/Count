package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.TransactionDTO;
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

    @PostMapping("/payment-maintenance/list")
    public ResponseEntity<Map<String, Object>> listPaymentMaintenance(
            @RequestBody TransactionDTO.PaymentMaintenanceRequest request) {
        try {
            List<TransactionDTO.PaymentMaintenanceRow> rows =
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
    public ResponseEntity<Map<String, Object>> deletePaymentMaintenance(
            @RequestBody TransactionDTO.PaymentMaintenanceDeleteRequest request) {
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
    public ResponseEntity<Map<String, Object>> listBankProcessMaintenance(
            @RequestBody TransactionDTO.BankProcessMaintenanceRequest request) {
        try {
            List<TransactionDTO.BankProcessMaintenanceRow> rows =
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
    public ResponseEntity<Map<String, Object>> deleteBankProcessMaintenance(
            @RequestBody TransactionDTO.BankProcessMaintenanceDeleteRequest request) {
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

    private static ResponseEntity<Map<String, Object>> error(BusinessException e) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
