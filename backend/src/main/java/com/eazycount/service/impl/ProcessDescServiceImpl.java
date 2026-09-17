package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.Audited;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.ProcessDescDao;
import com.eazycount.entity.AuditLog;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.security.SessionUser;
import com.eazycount.service.ProcessDescService;
import com.eazycount.util.AccessControlUtils;
import com.eazycount.util.AssertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProcessDescServiceImpl implements ProcessDescService {

    @Autowired
    private ProcessDescDao processDescDao;

    @Override
    public List<ProcessDescription> findDescriptionByTenantId(Integer tenantId) {
        SessionUser sessionUser = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireValidTenantId(tenantId);
        return processDescDao.findDescriptionByTenantId(tenantId);
    }

    @Override
    @Audited(module = "PROCESS_DESCRIPTION", action = AuditLog.Action.CREATE, entityIdExpr = "#processDescription.id", sourceTable = "process_description")
    @Transactional
    public void insertNewProcessDescription(ProcessDescription processDescription) {
        SessionUser sessionUser = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(sessionUser);
        AccessControlUtils.requireValidTenantId(processDescription != null ? processDescription.getTenantId() : null);
        if (processDescription.getName() == null || processDescription.getName().isBlank()) {
            throw new BusinessException("Description name is required!");
        }

        processDescription.setName(processDescription.getName().trim().toUpperCase());

        ProcessDescription existing = processDescDao.findDescriptionByName(
                processDescription.getName(), processDescription.getTenantId());
        if (existing != null) {
            throw new BusinessException("Description name already exists!");
        }

        try {
            processDescDao.insertNewProcessDescription(processDescription);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("Description name already exists!");
        } catch (Exception e) {
            throw new BusinessException("Insert failed. Please try again!");
        }

        AuditContext.captureAfter(processDescription.getId(), processDescriptionSnapshot(processDescription));
    }

    @Override
    @Audited(module = "PROCESS_DESCRIPTION", action = AuditLog.Action.DELETE, entityIdExpr = "#id", sourceTable = "process_description")
    @Transactional
    public void deleteProcessDescriptionById(Integer id, Integer tenantId) {
        SessionUser sessionUser = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(sessionUser);
        if (id == null) {
            throw new BusinessException("id is required!");
        }
        AccessControlUtils.requireValidTenantId(tenantId);

        ProcessDescription processDescription = AssertUtils.requireFound(
                processDescDao.findDescriptionByIdAndTenantId(id, tenantId), "Description does not exist!");
        AuditContext.captureBefore(id, processDescriptionSnapshot(processDescription));

        try {
            processDescDao.deleteProcessDescriptionById(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Delete failed. Please try again!");
        }
    }

    /** {@code process_description} column names, not {@link ProcessDescription}'s Java field names — see docs/it-role-audit-log.md. */
    private static Map<String, Object> processDescriptionSnapshot(ProcessDescription d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", d.getId());
        snapshot.put("tenant_id", d.getTenantId());
        snapshot.put("name", d.getName());
        snapshot.put("created_at", d.getCreatedAt());
        return snapshot;
    }
}
