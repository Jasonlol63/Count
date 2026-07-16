package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;
import com.eazycount.service.BankProcessService;
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
@RequestMapping("/api/bank-process")
public class BankProcessController {

    @Autowired
    private BankProcessService bankProcessService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> processList(@RequestBody Integer tenantId) {
        try {
            final List<BankProcessDTO> bankProcess = bankProcessService.findAllBankProcess(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Bank process retrieved successfully",
                    "data", bankProcess
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/add-bank-process")
    public ResponseEntity<Map<String, Object>> addBankProcess(@RequestBody BankProcessDTO bankProcessDTO) {
        try {
            BankProcessDTO created = bankProcessService.insertBankProcess(bankProcessDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Bank process inserted successfully");
            body.put("data", created);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/update-bank-process")
    public ResponseEntity<Map<String, Object>> updateBankProcess(@RequestBody BankProcessDTO bankProcessDTO) {
        try {
            BankProcessDTO created = bankProcessService.updateBankProcessDetails(bankProcessDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Bank process updated successfully");
            body.put("data", created);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/delete-bank-process")
    public ResponseEntity<Map<String, Object>> deleteBankProcess(@RequestBody BankProcess bankProcess) {
        try {
            bankProcessService.deleteBankProcess(bankProcess.getId(), bankProcess.getTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Bank process deleted successfully");
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/update-status")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody BankProcess bankProcess) {
        try{
            BankProcess update = bankProcessService.updateBankProcessStatus(bankProcess.getId(), bankProcess.getTenantId(), bankProcess.getStatus());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "BankProcess Status updated successfully");
            body.put("data", update);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/update-remark")
    public ResponseEntity<Map<String, Object>> updateRemark(@RequestBody BankProcess bankProcess) {
        try{
            bankProcessService.updateBankProcessRemark(bankProcess.getId(), bankProcess.getTenantId(), bankProcess.getRemark());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "BankProcess Remark updated successfully");
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
