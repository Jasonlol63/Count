package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.TransactionContraInboxDTO;
import com.eazycount.service.TransactionContraInboxService;
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
@RequestMapping("/api")
public class TransactionContraInboxController {

    @Autowired
    private TransactionContraInboxService transactionContraInboxService;

    @PostMapping("/approved")
    public ResponseEntity<Map<String, Object>> approved(@RequestBody TransactionContraInboxDTO txnContraInboxDTO) {
        try{
            transactionContraInboxService.approve(txnContraInboxDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Contra Approved Successfully");
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/rejected")
    public ResponseEntity<Map<String, Object>> rejected(@RequestBody TransactionContraInboxDTO txnContraInboxDTO) {
        try{
            transactionContraInboxService.reject(txnContraInboxDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Contra Rejected Successfully");
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/pending")
    public ResponseEntity<Map<String, Object>> pending(@RequestBody TransactionContraInboxDTO txnContraInboxDTO) {
        try{
            List<TransactionContraInboxDTO> rows = transactionContraInboxService.listPending(
                    txnContraInboxDTO != null ? txnContraInboxDTO.getTenantId() : null);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", rows);
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
