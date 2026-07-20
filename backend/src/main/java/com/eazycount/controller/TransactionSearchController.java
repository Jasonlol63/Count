package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.TransactionDTO;
import com.eazycount.service.TransactionHistoryService;
import com.eazycount.service.TransactionSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/transaction")
public class TransactionSearchController {

    @Autowired
    private TransactionSearchService transactionSearchService;

    @Autowired
    private TransactionHistoryService transactionHistoryService;

    /** Bank Process post lines only; flat rows — frontend splits +/- balance columns. */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody TransactionDTO.SearchRequest request) {
        try {
            TransactionDTO.SearchResult data = transactionSearchService.searchBankProcess(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Transaction search completed",
                    "data", data
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    /** Bank Process post line detail for one account (Payment History v1). */
    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestBody TransactionDTO.HistoryRequest request) {
        try {
            TransactionDTO.HistoryResult data = transactionHistoryService.historyBankProcess(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Transaction history retrieved",
                    "data", data
            ));
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
