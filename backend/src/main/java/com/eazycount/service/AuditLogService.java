package com.eazycount.service;

import com.eazycount.dto.AuditLogDTO;

public interface AuditLogService {

    /**
     * Records one audit_log row. Called either explicitly (a Service method calling this
     * directly) or automatically by {@link com.eazycount.audit.AuditLogAspect} for methods
     * annotated {@link com.eazycount.audit.Audited}. Captures operator/tenant from the current
     * session automatically.
     */
    void record(AuditLogDTO request);

    /** IT-only; throws if the current session isn't role=="it". Returns a DTO with items/total populated. */
    AuditLogDTO search(AuditLogDTO query, int page, int size);

    /** IT-only; same filters as {@link #search}, no pagination. Returns a DTO with the count fields populated. */
    AuditLogDTO summary(AuditLogDTO query);
}
