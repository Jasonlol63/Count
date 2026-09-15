package com.eazycount.audit;

import com.eazycount.entity.AuditLog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Service method for automatic audit-log capture — see docs/it-role-audit-log.md.
 * {@link AuditLogAspect} records one {@code audit_log} row per id resolved from
 * {@link #entityIdExpr()} once the method returns successfully (never on failure).
 *
 * <p>Named {@code Audited}, not {@code AuditLog}, to avoid colliding with the
 * {@link com.eazycount.entity.AuditLog} entity.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** e.g. "PAYMENT_MAINTENANCE" */
    String module();

    AuditLog.Action action();

    /**
     * SpEL evaluated against the method's parameters (by name — e.g. {@code "#request.transactionIds"}).
     * May resolve to a single value or a {@link java.util.Collection} — one audit_log row is written
     * per resolved id, so a batch operation produces one row per affected entity, not one row per call.
     */
    String entityIdExpr();

    /** Real DB table name, stored on the log row for manual-recovery reference. */
    String sourceTable();

    /**
     * Whether restore is expected to be wired up for this module. The aspect does not itself
     * capture before/after snapshots for a restorable=true call — the method must still call
     * {@link AuditContext#captureBefore}/{@link AuditContext#captureBeforeBatch} itself, since
     * only the method body has the entity's pre-write state.
     */
    boolean restorable() default false;
}
