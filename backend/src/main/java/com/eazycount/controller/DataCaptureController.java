package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.DataCaptureBankDTO;
import com.eazycount.dto.DataCaptureGameDTO;
import com.eazycount.service.DataCaptureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/datacapture")
public class DataCaptureController {

    @Autowired
    private DataCaptureService dataCaptureService;

    @PostMapping("/games/form")
    public ResponseEntity<Map<String, Object>> loadGameCaptureForm(@RequestBody DataCaptureGameDTO request) {
        try {
            DataCaptureGameDTO data = dataCaptureService.loadGameCaptureForm(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "success",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/bank/draft/save")
    public ResponseEntity<Map<String, Object>> saveBankDraft(@RequestBody DataCaptureBankDTO request) {
        try {
            DataCaptureBankDTO data = dataCaptureService.saveBankDraft(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Draft saved",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/bank/draft/get")
    public ResponseEntity<Map<String, Object>> getBankDraft(@RequestBody DataCaptureBankDTO request) {
        try {
            DataCaptureBankDTO data = dataCaptureService.getBankDraft(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "success",
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
