package com.eazycount.service.impl;

import com.eazycount.audit.AuditSummaryDefaults;
import com.eazycount.dao.AuditLogDao;
import com.eazycount.dao.DomainDao;
import com.eazycount.dto.AuditLogDTO;
import com.eazycount.entity.AuditLog;
import com.eazycount.entity.Tenant;
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
    private DomainDao domainDao;
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
            entry.setSummary(resolveSummary(request, session.tenant_code, session.tenant_id));
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

    /**
     * The writing method's own {@link com.eazycount.audit.AuditContext#captureSummary} wins when
     * present; otherwise {@link AuditSummaryDefaults} builds a generic one. Either way, the
     * tenant name is appended here rather than by the caller — this is the one place that
     * reliably knows the operator's tenant code, and it's the same suffix for every module.
     * "公司"/"集团" is picked from the tenant's own {@code tenant_type}, not hardcoded — a GROUP
     * tenant reads "在 XX 集团", a COMPANY tenant "在 XX 公司".
     */
    private String resolveSummary(AuditLogDTO request, String tenantCode, Integer tenantId) {
        String summary = request.getSummary();
        if (summary == null) {
            summary = AuditSummaryDefaults.build(
                    request.getModule(), request.getSourceTable(), request.getAction(),
                    request.getBeforeData(), request.getAfterData());
        }
        if (summary != null && tenantCode != null && !tenantCode.isBlank()) {
            summary = summary + " 在 " + tenantCode + " " + tenantUnitLabel(tenantId);
        }
        return summary;
    }

    private String tenantUnitLabel(Integer tenantId) {
        if (tenantId == null) {
            return "公司";
        }
        try {
            Tenant tenant = domainDao.findTenantById(tenantId);
            return tenant != null && tenant.getTenantType() == Tenant.TenantType.GROUP ? "集团" : "公司";
        } catch (Exception e) {
            log.warn("Failed to resolve tenant type for audit summary — tenantId={}", tenantId, e);
            return "公司";
        }
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
