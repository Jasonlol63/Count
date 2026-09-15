package com.eazycount.controller;

import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;
import com.eazycount.dto.TransactionSearchRequest;
import com.eazycount.dto.TransactionSearchResult;
import com.eazycount.dto.TransactionSubmitDTO;
import com.eazycount.service.TransactionHistoryService;
import com.eazycount.service.TransactionSearchService;
import com.eazycount.service.TransactionSubmitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/transaction")
public class TransactionController {

    @Autowired
    private TransactionSearchService transactionSearchService;

    @Autowired
    private TransactionHistoryService transactionHistoryService;

    @Autowired
    private TransactionSubmitService transactionSubmitService;

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody TransactionSearchRequest request) {
        TransactionSearchResult data = transactionSearchService.searchList(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Transaction search completed",
                "data", data
        ));
    }

    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestBody TransactionHistoryRequest request) {
        TransactionHistoryResult data = transactionHistoryService.historyList(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Transaction history retrieved",
                "data", data
        ));
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody TransactionSubmitDTO request) {
        TransactionSubmitDTO data = transactionSubmitService.submit(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Transaction submitted",
                "data", data
        ));
    }
}
