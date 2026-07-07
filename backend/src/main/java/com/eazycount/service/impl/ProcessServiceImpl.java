package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.ProcessDao;
import com.eazycount.dto.ProcessDescriptionDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.ProcessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProcessServiceImpl implements ProcessService {

    @Autowired
    private ProcessDao processDao;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<ProcessDescriptionDTO> findAllProcessByTenantId(Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        return processDao.findAllProcessByTenantId(tenantId);
    }

    @Override
    @Transactional
    public void insertProcessDetails(ProcessDescriptionDTO processDescDTO) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        String settingsJson = "{}";
        String descriptionIdsJson = "[]";
        String scheduleDaysJson = "[]";

        try {
            Map<String, String> settingsMap = new HashMap<>();
            settingsMap.put("remove_word",
                    processDescDTO.getRemoveWord() != null ? processDescDTO.getRemoveWord() : "");
            settingsMap.put("replace_word_from",
                    processDescDTO.getReplaceWordFrom() != null ? processDescDTO.getReplaceWordFrom() : "");
            settingsMap.put("replace_word_to",
                    processDescDTO.getReplaceWordTo() != null ? processDescDTO.getReplaceWordTo() : "");
            settingsJson = objectMapper.writeValueAsString(settingsMap);
            List<Integer> descIds = processDescDTO.getDescriptionIds() != null ? processDescDTO.getDescriptionIds()
                    : new ArrayList<>();
            descriptionIdsJson = objectMapper.writeValueAsString(descIds);

            if (processDescDTO.getDayUse() != null && !processDescDTO.getDayUse().trim().isEmpty()) {
                List<Integer> days = new ArrayList<>();
                for (String d : processDescDTO.getDayUse().split(",")) {
                    days.add(Integer.parseInt(d.trim()));
                }
                scheduleDaysJson = objectMapper.writeValueAsString(days);
            }
        } catch (Exception e) {
            throw new BusinessException("JSON serialization failed: " + e.getMessage());
        }

        Process process = new Process();
        process.setTenantId(processDescDTO.getTenantId());
        process.setCode(processDescDTO.getCode().trim());
        process.setCurrencyId(processDescDTO.getCurrencyId());
        process.setDescriptionIds(descriptionIdsJson);
        process.setScheduleDays(scheduleDaysJson);
        process.setSettings(settingsJson);
        process.setRemark(processDescDTO.getRemark());
        process.setStatus(Process.Status.ACTIVE);
        process.setCreatedBy(session.user_id);
        try {
            processDao.insertProcessDetails(process);
        } catch (Exception e) {
            throw new BusinessException("Failed to insert process: " + processDescDTO.getCode());
        }
    }

    @Override
    @Transactional
    public String updateStatusById(Integer id) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (id == null) {
            throw new BusinessException("id is required!");
        }

        Process process = processDao.findProcessByIdAndTenantId(id, session.tenant_id);
        if (process == null) {
            throw new BusinessException("Process not found for id: " + id);
        }

        Process.Status currentStatus = process.getStatus();
        Process.Status newStatus = (currentStatus == Process.Status.ACTIVE) ? Process.Status.INACTIVE
                : Process.Status.ACTIVE;

        try {
            processDao.updateStatusById(id, String.valueOf(newStatus));
        } catch (Exception e) {
            throw new BusinessException("Failed to update status");
        }

        return String.valueOf(newStatus).toLowerCase();
    }

    @Override
    public List<ProcessDescription> findAllDescription(Integer tenantId) {

        if (tenantId == null) {
            throw new BusinessException("tenantId is required!");
        }

        return processDao.findDescriptionsByTenantId(tenantId);

    }

    @Override
    public void insertNewDescription(ProcessDescription processDescription) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        try {

            ProcessDescription checked = processDao.findDescriptionByNameAndTenantId(processDescription.getTenantId(),
                    processDescription.getName());
            if (checked == null) {
                processDao.insertNewDescription(processDescription);
            } else {
                processDescription.setId(checked.getId());
            }

        } catch (Exception e) {
            throw new BusinessException("Failed to insert process description");
        }

    }

    @Override
    public void deleteDescriptionById(Integer id, Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        ProcessDescription find = processDao.findDescriptionById(id);
        if (find == null) {
            throw new BusinessException("Process description not found!");
        }

        if (!find.getTenantId().equals(tenantId)) {
            throw new BusinessException("Unauthorized operation!");
        }

        try {
            processDao.deleteDescriptionById(id);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete process description");
        }
    }
}
