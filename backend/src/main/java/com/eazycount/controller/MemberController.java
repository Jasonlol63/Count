package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.MemberPageDTO;
import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;
import com.eazycount.dto.UserCurrencyDTO;
import com.eazycount.service.UserPageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/member")
public class MemberController {

    @Autowired
    private UserPageService userPageService;

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile() {
        try {
            MemberPageDTO data = userPageService.getMemberProfile();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "",
                    "data", data));
        } catch (BusinessException e){
            return error(e);
        }
    }

    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestBody TransactionHistoryRequest request) {
        try {
            TransactionHistoryResult data = userPageService.getMemberHistory(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    /* Own currencies for one account in the Account selector (self, or one visible via Account Link). */
    @PostMapping("/account-currencies")
    public ResponseEntity<Map<String, Object>> accountCurrencies(@RequestBody Integer accountId) {
        try {
            List<UserCurrencyDTO> data = userPageService.getMemberAccountCurrencies(accountId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    /* Own currencies for several accounts in one call (mini grid) — self + accounts visible via Account Link. */
    @PostMapping("/account-currencies/batch")
    public ResponseEntity<Map<String, Object>> accountCurrenciesBatch(@RequestBody MemberPageDTO.BatchRequest request) {
        try {
            List<Integer> ids = request == null ? List.of() : request.getAccountIds();
            List<MemberPageDTO.AccountCurrencies> data = userPageService.getMemberAccountsCurrencies(ids);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    /* Closing balance per (account, currency) for the mini grid, in one call. */
    @PostMapping("/mini-grid-balances")
    public ResponseEntity<Map<String, Object>> miniGridBalances(@RequestBody MemberPageDTO.BatchRequest request) {
        try {
            List<MemberPageDTO.AccountCurrencyBalance> data = userPageService.getMemberMiniGridBalances(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "",
                    "data", data));
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
