package com.eazycount.controller;

import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.dto.UserLinkedDTO;
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
        final List<Currency> data = currencyService.findCurrencyByTenantId(tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Currency retrieved successfully",
                "data", data
        ));
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Currency currency) {
        final Currency cur = currencyService.addNewCurrency(currency);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Currency insert successfully",
                "data", cur
        ));
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam(value = "id") Integer id,  @RequestParam(value = "tenantId") Integer tenantId) {
        currencyService.deleteCurrencyByIdAndTenantId(id, tenantId);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Currency deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

    @PostMapping ("/available")
    public ResponseEntity<Map<String, Object>> accountCurrency(@RequestParam("tenant_id") Integer tenantId, @RequestParam(value = "account_id", required = false) Integer accountId) {
        final List<UserCurrencyDTO> data = currencyService.findAvailableCurrencies(tenantId, accountId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Currency retrieved successfully",
                "data", data
        ));
    }

    @PostMapping("/account/linked-accounts")
    public ResponseEntity<Map<String, Object>> linkedAccounts(@RequestParam("currency_id") Integer currencyId, @RequestParam("tenant_id") Integer tenantId) {
        final UserLinkedDTO data =
                currencyService.findLinkedAccountsByCurrencyIdAndTenantId(currencyId, tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Linked accounts retrieved successfully",
                "data", data
        ));
    }

    @PostMapping("/account/linked-accounts-update")
    public ResponseEntity<Map<String, Object>> UpdateLinkedAccounts(@RequestBody UserLinkedDTO request) {
        currencyService.bulkUpdateAccountCurrency(request);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Currency settings saved");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
