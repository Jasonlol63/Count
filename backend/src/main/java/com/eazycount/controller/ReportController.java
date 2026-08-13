package com.eazycount.controller;

import com.eazycount.common.BusinessException;
import com.eazycount.dto.CustomerReportDTO;
import com.eazycount.dto.DomainReportDTO;
import com.eazycount.service.ReportService;
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
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @PostMapping("/customer-report/list")
    public ResponseEntity<Map<String, Object>> listCustomerReport(@RequestBody CustomerReportDTO cr){
        try{
            List<CustomerReportDTO> result = reportService.findCustomerReportRows(cr);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Customer Report List retrieved successfully",
                    "data", result
            ));
        }catch (BusinessException e){
            return error(e);
        }
    }

    @PostMapping("/domain-report/list")
    public ResponseEntity<Map<String, Object>> listDomainReport(@RequestBody DomainReportDTO dr){
        try{
            List<DomainReportDTO> result = reportService.findDomainReportRows(dr);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Domain Report List retrieved successfully",
                    "data", result
            ));
        }catch (BusinessException e){
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
