package com.eazycount.service.impl;

import com.eazycount.dao.AuditLogDao;
import com.eazycount.dto.AuditLogDTO;
import com.eazycount.entity.AuditLog;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AuditLogService;
import com.eazycount.util.AccessControlUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private AuditLogDao auditLogDao;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void record(AuditLogDTO request) {
        // Best-effort: a broken audit call must never take down the real write it's
        // piggybacking on (e.g. a payment delete succeeding but its audit row failing
        // to serialize shouldn't roll back to delete itself).
        try {
            final SessionUser session = SecurityUtils.currentUser();
            if (session == null) {
                log.warn("AuditLogService.record() called with no session — module={}, action={}",
                        request.getModule(), request.getAction());
                return;
            }

            AuditLog entry = new AuditLog();
            entry.setOperatorId(session.user_id != null ? String.valueOf(session.user_id) : null);
            entry.setOperatorName(session.name);
            entry.setOperatorRole(session.role);
            entry.setTenantId(session.tenant_id);
            entry.setTenantCode(session.tenant_code);
            entry.setModule(request.getModule());
            entry.setAction(request.getAction());
            entry.setEntityId(request.getEntityId());
            entry.setSourceTable(request.getSourceTable());
            entry.setSummary(request.getSummary());
            entry.setBeforeData(toJson(request.getBeforeData()));
            entry.setAfterData(toJson(request.getAfterData()));
            entry.setRestorable(request.isRestorable());
            entry.setRestored(false);
            entry.setRelatedLogId(request.getRelatedLogId());

            auditLogDao.insert(entry);
        } catch (Exception e) {
            log.warn("AuditLogService.record() failed — module={}, action={}, entityId={}",
                    request.getModule(), request.getAction(), request.getEntityId(), e);
        }
    }

    @Override
    public AuditLogDTO search(AuditLogDTO query, int page, int size) {
        AccessControlUtils.requireItOperator(SecurityUtils.currentUser());

        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        query.setOffset((safePage - 1) * safeSize);
        query.setLimit(safeSize);

        List<AuditLog> items = auditLogDao.search(query);
        long total = auditLogDao.countSearch(query);

        AuditLogDTO result = new AuditLogDTO();
        result.setItems(items);
        result.setTotal(total);
        return result;
    }

    @Override
    public AuditLogDTO summary(AuditLogDTO query) {
        AccessControlUtils.requireItOperator(SecurityUtils.currentUser());
        return auditLogDao.summary(query);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log snapshot", e);
            return null;
        }
    }
}
