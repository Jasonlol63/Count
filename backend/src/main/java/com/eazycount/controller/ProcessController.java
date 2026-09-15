package com.eazycount.controller;

import com.eazycount.dto.ProcessDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.service.ProcessDescService;
import com.eazycount.service.ProcessService;
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
@RequestMapping("/api/process")
public class ProcessController {

    @Autowired
    private ProcessService processService;

    @Autowired
    private ProcessDescService processDescService;

    @PostMapping("/process-list")
    public ResponseEntity<Map<String, Object>> getProcessList(@RequestBody Integer tenantId) {
        List<ProcessDTO> list = processService.findProcessByTenantId(tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Process List retrieved successfully",
                "data", list
        ));
    }

    @PostMapping("/add-process")
    public ResponseEntity<Map<String, Object>> addNewProcess(@RequestBody ProcessDTO processDTO) {
        ProcessDTO created = processService.addNewProcess(processDTO);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Process inserted successfully");
        body.put("data", created);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update-process")
    public ResponseEntity<Map<String, Object>> updateProcess(@RequestBody ProcessDTO processDTO) {
        ProcessDTO updated = processService.updateProcess(processDTO);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Process updated successfully");
        body.put("data", updated);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/delete-process")
    public ResponseEntity<Map<String, Object>> deleteProcess(@RequestBody Process process) {
        processService.deleteProcessById(process.getId(), process.getTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Process deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update-status")
    public ResponseEntity<Map<String, Object>> updateStatus(@RequestBody Process process) {
        Process update = processService.updateProcessStatus(process.getId(), process.getTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Process Status updated successfully");
        body.put("data", update);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/list-description")
    public ResponseEntity<Map<String, Object>> getProcessDescriptionList(@RequestBody Integer tenantId) {
        List<ProcessDescription> list = processDescService.findDescriptionByTenantId(tenantId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Process Description List retrieved successfully",
                "data", list
        ));
    }

    @PostMapping("/add-description")
    public ResponseEntity<Map<String, Object>> insertNewDescription(@RequestBody ProcessDescription processDescription) {
        processDescService.insertNewProcessDescription(processDescription);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Process Description inserted successfully",
                "data", processDescription
        ));
    }

    @PostMapping("/delete-description")
    public ResponseEntity<Map<String, Object>> deleteDescription(@RequestBody ProcessDescription desc) {
        processDescService.deleteProcessDescriptionById(desc.getId(), desc.getTenantId());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Process Description deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }
}
