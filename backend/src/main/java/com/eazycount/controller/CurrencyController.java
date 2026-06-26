package com.eazycount.controller;

import com.eazycount.entity.Currency;
import com.eazycount.service.CurrencyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/currency")
public class CurrencyController {

    @Autowired
    private CurrencyService currencyService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "tenant_id") Integer tenantId) {
        try {
            final List<Currency> data = currencyService.findCurrencyByTenantId(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Currency retrieved successfully",
                    "data", data
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Currency currency) {
        try {
            final Currency cur = currencyService.addNewCurrency(currency);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Currency insert successfully",
                    "data", cur
            ));
        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam(value = "id") Integer id,  @RequestParam(value = "tenantId") Integer tenantId) {
        try {
            currencyService.deleteCurrencyByIdAndTenantId(id, tenantId);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Currency deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);

        } catch (com.eazycount.common.BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
