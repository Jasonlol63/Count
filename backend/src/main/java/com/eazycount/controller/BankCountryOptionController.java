package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.service.BankCountryOptionService;
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
@RequestMapping("/api/bank-country-option")
public class BankCountryOptionController {

    @Autowired
    private BankCountryOptionService bankCountryOptionService;

    @PostMapping("/list-country")
    public ResponseEntity<Map<String, Object>> listCountry(@RequestBody Integer tenantId) {
        try {
            final List<BankCountry> bankCountries = bankCountryOptionService.findAllCountry(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Country retrieved successfully",
                    "data", bankCountries
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    /**
     * Body: {@code { "tenantId": 1, "countryId": 2 }}
     * (reuse {@link BankOption} fields; other fields ignored)
     */
    @PostMapping("/list-bank-option")
    public ResponseEntity<Map<String, Object>> listBankOption(@RequestBody BankOption request) {
        try {
            final List<BankOption> bankOptions = bankCountryOptionService.findAllBankInCountry(
                    request.getTenantId(), request.getCountryId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Bank options retrieved successfully",
                    "data", bankOptions
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/insert-country")
    public ResponseEntity<Map<String, Object>> insertBankCountry(@RequestBody BankCountry bankCountry) {
        try {
            bankCountryOptionService.insertNewCountry(bankCountry);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Country inserted successfully",
                    "data", bankCountry
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/insert-bank-option")
    public ResponseEntity<Map<String, Object>> insertBankOption(@RequestBody BankOption bankOption) {
        try {
            bankCountryOptionService.insertNewBankOption(bankOption);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Bank option inserted successfully",
                    "data", bankOption
            ));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/delete-country")
    public ResponseEntity<Map<String, Object>> deleteCountry(@RequestBody BankCountry bankCountry) {
        try {
            bankCountryOptionService.deleteCountryByIdAndTenantId(
                    bankCountry.getId(), bankCountry.getTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Country deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/delete-bank-option")
    public ResponseEntity<Map<String, Object>> deleteBankOption(@RequestBody BankOption bankOption) {
        try {
            bankCountryOptionService.deleteBankOptionByIdAndTenantId(
                    bankOption.getId(), bankOption.getTenantId(), bankOption.getCountryId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Bank option deleted successfully");
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
