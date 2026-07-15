package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.BankProcessDTO;
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

    private static ResponseEntity<Map<String, Object>> error(BusinessException e) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
