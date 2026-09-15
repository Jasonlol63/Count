package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;
import com.eazycount.service.BankProcessResendService;
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

    @Autowired
    private BankProcessResendService bankProcessResendService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> processList(@RequestBody Integer tenantId) {
        final List<BankProcessDTO> bankProcess = bankProcessService.findAllBankProcess(tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Bank process retrieved successfully",
                "data", bankProcess
        ));
    }

    @PostMapping("/add-bank-process")
    public ResponseEntity<Map<String, Object>> addBankProcess(@RequestBody BankProcessDTO bankProcessDTO) {
        BankProcessDTO created = bankProcessService.insertBankProcess(bankProcessDTO);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Bank process inserted successfully");
        body.put("data", created);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update-bank-process")
    public ResponseEntity<Map<String, Object>> updateBankProcess(@RequestBody BankProcessDTO bankProcessDTO) {
        BankProcessDTO created = bankProcessService.updateBankProcessDetails(bankProcessDTO);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Bank process updated successfully");
        body.put("data", created);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/delete-bank-process")
    public ResponseEntity<Map<String, Object>> deleteBankProcess(@RequestBody BankProcess bankProcess) {
        bankProcessService.deleteBankProcess(bankProcess.getId(), bankProcess.getTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Bank process deleted successfully");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update-status")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody BankProcess bankProcess) {
        BankProcess update = bankProcessService.updateBankProcessStatus(bankProcess.getId(), bankProcess.getTenantId(), bankProcess.getStatus());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "BankProcess Status updated successfully");
        body.put("data", update);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update-remark")
    public ResponseEntity<Map<String, Object>> updateRemark(@RequestBody BankProcess bankProcess) {
        bankProcessService.updateBankProcessRemark(bankProcess.getId(), bankProcess.getTenantId(), bankProcess.getRemark());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "BankProcess Remark updated successfully");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/delete-bank-balance")
    public ResponseEntity<Map<String, Object>> deleteBankBalance(@RequestBody BankProcess bankProcess) {
        bankProcessService.deleteBankBalance(bankProcess.getId(), bankProcess.getTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Bank Balance deleted successfully");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/resend")
    public ResponseEntity<Map<String, Object>> resend(@RequestBody AccountingDueDTO request) {
        if (request == null || request.getTenantId() == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        final AccountingDueDTO data = bankProcessResendService.resend(request);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Resend successful");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
