package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.DataCaptureSummaryDTO;
import com.eazycount.dto.DataCaptureSummarySubmitDTO;
import com.eazycount.service.DataCaptureSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/datacapture-summary")
public class DataCaptureSummaryController {

    @Autowired
    private DataCaptureSummaryService dataCaptureSummaryService;

    @PostMapping("/formula/save")
    public ResponseEntity<Map<String, Object>> saveAddFormula(@RequestBody DataCaptureSummaryDTO request) {
        try {
            DataCaptureSummaryDTO data = dataCaptureSummaryService.saveAddFormula(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Formula Saved Successfully",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/formula/update")
    public ResponseEntity<Map<String, Object>> updateFormula(@RequestBody DataCaptureSummaryDTO request) {
        try {
            DataCaptureSummaryDTO data = dataCaptureSummaryService.updateFormula(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Formula Updated Successfully",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/formula/delete")
    public ResponseEntity<Map<String, Object>> deleteFormulas(@RequestBody DataCaptureSummaryDTO request) {
        try {
            DataCaptureSummaryDTO data = dataCaptureSummaryService.deleteFormulas(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Formula Deleted Successfully",
                    "data", data));
        } catch (BusinessException e) {
            return error(e);
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody DataCaptureSummarySubmitDTO request) {
        try {
            DataCaptureSummarySubmitDTO data = dataCaptureSummaryService.submit(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Data Capture Submitted Successfully",
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
