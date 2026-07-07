package com.eazycount.controller;


import com.eazycount.common.BusinessException;
import com.eazycount.entity.Process;
import com.eazycount.dto.ProcessDescriptionDTO;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.service.ProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/process")
public class ProcessController {

    @Autowired
    private ProcessService processService;

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "tenant_id") Integer tenantId){
        try{
            final List<ProcessDescriptionDTO> data = processService.findAllProcessByTenantId(tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Process List retrieved successfully",
                    "data", data));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addProcessList(@RequestBody ProcessDescriptionDTO processDescriptionDTO){
        try{
            processService.insertProcessDetails(processDescriptionDTO);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Process created successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }


    @PostMapping("/update-status")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody Process process){
        try {
            String newStatus = processService.updateStatusById(process.getId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Process Status updated successfully",
                    "data", Map.of("newStatus", newStatus)));
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @GetMapping("/list-description")
    public ResponseEntity<Map<String, Object>> listDescription(@RequestParam(value = "tenant_id") Integer tenantId){
        try{
            final List <ProcessDescription> data = processService.findAllDescription(tenantId);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Process Updated successfully");
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/add-description")
    public ResponseEntity<Map<String, Object>> addDescription(@RequestBody ProcessDescription processDescription){
        try{
            processService.insertNewDescription(processDescription);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Description created successfully");
            body.put("data", processDescription);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }

    @PostMapping("/delete-description")
    public ResponseEntity<Map<String, Object>> deleteDescription(@RequestBody ProcessDescription processDescription){
        try{
            processService.deleteDescriptionById(processDescription.getId(), processDescription.getTenantId());
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Description deleted successfully");
            body.put("data", null);
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            body.put("data", null);
            return ResponseEntity.ok(body);
        }
    }
}
