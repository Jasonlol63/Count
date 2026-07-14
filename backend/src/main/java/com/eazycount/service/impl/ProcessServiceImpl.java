package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.ProcessDao;
import com.eazycount.dto.ProcessDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDay;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.entity.ProcessDescriptionLink;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.ProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProcessServiceImpl implements ProcessService {

    @Autowired
    private ProcessDao processDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    public List<ProcessDTO> findProcessByTenantId(Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        return processDao.findProcessByTenantId(tenantId);
    }

    @Override
    @Transactional
    public ProcessDTO addNewProcess(ProcessDTO processDTO) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (processDTO == null) {
            throw new BusinessException("Request body is required!");
        }

        String code = processDTO.getCode().trim().toUpperCase();
        processDTO.setCode(code);

        if (currencyDao.findByIdAndTenantId(processDTO.getCurrencyId(), processDTO.getTenantId()) == null) {
            throw new BusinessException("Currency not found!");
        }

        if (processDao.findProcessCodeByTenantId(processDTO.getTenantId(), code) != null) {
            throw new BusinessException("Process code already exists!");
        }

        Process process = new Process();
        process.setTenantId(processDTO.getTenantId());
        process.setCode(code);
        process.setCurrencyId(processDTO.getCurrencyId());
        process.setRemoveWord(processDTO.getRemoveWord());
        process.setReplaceWordFrom(processDTO.getReplaceWordFrom());
        process.setReplaceWordTo(processDTO.getReplaceWordTo());
        process.setRemark(processDTO.getRemark());
        process.setStatus(Process.Status.ACTIVE);
        process.setCreatedBy(sessionUser.login_id);

        try {
            processDao.insertNewProcess(process);
        } catch (Exception e) {
            throw new BusinessException("Insert process failed. Please try again!");
        }
        if (process.getId() == null) {
            throw new BusinessException("Insert process failed. Please try again!");
        }

        List<Integer> descriptionIds = processDTO.getDescriptionIds();
        if (descriptionIds != null && !descriptionIds.isEmpty()) {
            List<ProcessDescriptionLink> links = new ArrayList<>();
            Set<Integer> seenDesc = new LinkedHashSet<>();
            for (Integer descriptionId : descriptionIds) {
                if (descriptionId == null || descriptionId <= 0 || !seenDesc.add(descriptionId)) {
                    continue;
                }
                ProcessDescription desc = processDao.findDescriptionByIdAndTenantId(
                        descriptionId, processDTO.getTenantId());
                if (desc == null) {
                    throw new BusinessException("Description not found: " + descriptionId);
                }
                links.add(new ProcessDescriptionLink(null, process.getId(), descriptionId, null));
            }
            if (!links.isEmpty()) {
                try {
                    processDao.insertProcessDescriptionLinkBatch(links);
                } catch (Exception e) {
                    throw new BusinessException("Insert process description links failed!");
                }
            }
        }

        List<Integer> dayOfWeeks = processDTO.getDayOfWeeks();
        if (dayOfWeeks != null && !dayOfWeeks.isEmpty()) {
            List<ProcessDay> days = new ArrayList<>();
            Set<Integer> seenDays = new LinkedHashSet<>();
            for (Integer day : dayOfWeeks) {
                if (day == null || day < 1 || day > 7 || !seenDays.add(day)) {
                    continue;
                }
                days.add(new ProcessDay(null, process.getId(), day));
            }
            if (!days.isEmpty()) {
                try {
                    processDao.insertProcessDayBatch(days);
                } catch (Exception e) {
                    throw new BusinessException("Insert process days failed!");
                }
            }
        }

        processDTO.setId(process.getId());
        return processDTO;
    }

    @Override
    @Transactional
    public ProcessDTO updateProcess(ProcessDTO processDTO) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (processDTO == null) {
            throw new BusinessException("Request body is required!");
        }
        if (processDTO.getTenantId() == null) {
            throw new BusinessException("Tenant ID not found!");
        }
        if (processDTO.getId() == null) {
            throw new BusinessException("Process ID not found!");
        }

        Process existed = processDao.findProcessById(processDTO.getId());
        if (existed == null || !processDTO.getTenantId().equals(existed.getTenantId())) {
            throw new BusinessException("Process not found!");
        }

        if (currencyDao.findByIdAndTenantId(processDTO.getCurrencyId(), processDTO.getTenantId()) == null) {
            throw new BusinessException("Currency not found!");
        }

        Process process = new Process();
        process.setId(processDTO.getId());
        process.setTenantId(processDTO.getTenantId());
        process.setCurrencyId(processDTO.getCurrencyId());
        process.setRemoveWord(processDTO.getRemoveWord());
        process.setReplaceWordFrom(processDTO.getReplaceWordFrom());
        process.setReplaceWordTo(processDTO.getReplaceWordTo());
        process.setRemark(processDTO.getRemark());
        process.setUpdatedBy(sessionUser.login_id);
        processDao.updateProcessDetails(process);

        Integer processId = processDTO.getId();
        processDao.deleteProcessDescriptionLinkByProcessId(processId);
        processDao.deleteProcessDayByProcessId(processId);

        List<Integer> descriptionIds = processDTO.getDescriptionIds();
        if (descriptionIds != null && !descriptionIds.isEmpty()) {
            List<ProcessDescriptionLink> links = new ArrayList<>();
            Set<Integer> seenDesc = new LinkedHashSet<>();
            for (Integer descriptionId : descriptionIds) {
                if (descriptionId == null || descriptionId <= 0 || !seenDesc.add(descriptionId)) {
                    continue;
                }
                ProcessDescription desc = processDao.findDescriptionByIdAndTenantId(
                        descriptionId, processDTO.getTenantId());
                if (desc == null) {
                    throw new BusinessException("Description not found: " + descriptionId);
                }
                links.add(new ProcessDescriptionLink(null, processId, descriptionId, null));
            }
            if (!links.isEmpty()) {
                try {
                    processDao.insertProcessDescriptionLinkBatch(links);
                } catch (Exception e) {
                    throw new BusinessException("Insert process description links failed!");
                }
            }
        }

        List<Integer> dayOfWeeks = processDTO.getDayOfWeeks();
        if (dayOfWeeks != null && !dayOfWeeks.isEmpty()) {
            List<ProcessDay> days = new ArrayList<>();
            Set<Integer> seenDays = new LinkedHashSet<>();
            for (Integer day : dayOfWeeks) {
                if (day == null || day < 1 || day > 7 || !seenDays.add(day)) {
                    continue;
                }
                days.add(new ProcessDay(null, processId, day));
            }
            if (!days.isEmpty()) {
                try {
                    processDao.insertProcessDayBatch(days);
                } catch (Exception e) {
                    throw new BusinessException("Insert process days failed!");
                }
            }
        }

        return processDTO;
    }

    @Override
    @Transactional
    public void deleteProcessById(Integer id, Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null) {
            throw new BusinessException("id is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        Process process = processDao.findProcessByIdAndTenantId(id, tenantId);
        if (process == null) {
            throw new BusinessException("Process not found!");
        }
        if (process.getStatus() != Process.Status.INACTIVE) {
            throw new BusinessException("Process is not inactive, cannot be deleted!");
        }

        // Child rows (description_link / day / process_submitted) cascade from process FK.
        try {
            processDao.deleteProcessById(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete process");
        }
    }

    @Override
    public Process updateProcessStatus(Integer id, Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null) {
            throw new BusinessException("id is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        Process process = processDao.findProcessById(id);
        if (process == null || !tenantId.equals(process.getTenantId())) {
            throw new BusinessException("Process not found!");
        }

        Process.Status current = process.getStatus() != null
                ? process.getStatus()
                : Process.Status.ACTIVE;
        Process.Status newStatus = (current == Process.Status.ACTIVE)
                ? Process.Status.INACTIVE
                : Process.Status.ACTIVE;

        processDao.updateProcessStatus(id, tenantId, newStatus);

        Process result = processDao.findProcessById(id);
        if (result == null) {
            throw new BusinessException("Process not found!");
        }
        return result;
    }

    @Override
    public List<ProcessDescription> findDescriptionByTenantId(Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }
        return processDao.findDescriptionByTenantId(tenantId);
    }

    @Override
    public void insertNewProcessDescription(ProcessDescription processDescription) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (processDescription == null) {
            throw new BusinessException("Request body is required!");
        }
        if (processDescription.getTenantId() == null) {
            throw new BusinessException("tenant_id is required!");
        }
        if (processDescription.getName() == null || processDescription.getName().isBlank()) {
            throw new BusinessException("Description name is required!");
        }

        processDescription.setName(processDescription.getName().trim().toUpperCase());

        ProcessDescription existing = processDao.findDescriptionByName(
                processDescription.getName(), processDescription.getTenantId());
        if (existing != null) {
            throw new BusinessException("Description name already exists!");
        }

        try {
            processDao.insertNewProcessDescription(processDescription);
        } catch (Exception e) {
            throw new BusinessException("Insert failed. Please try again!");
        }
    }

    @Override
    public void deleteProcessDescriptionById(Integer id, Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null) {
            throw new BusinessException("id is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        ProcessDescription processDescription = processDao.findDescriptionByIdAndTenantId(id, tenantId);
        if (processDescription == null) {
            throw new BusinessException("Description does not exist!");
        }

        try {
            processDao.deleteProcessDescriptionById(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Delete failed. Please try again!");
        }
    }
}
