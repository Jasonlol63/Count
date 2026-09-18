package com.eazycount.audit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-local staging area for "before"/"after" snapshots that only the annotated method's own
 * body can see (an AOP aspect wrapping the method from the outside has no visibility into local
 * variables). Call {@link #captureBefore}/{@link #captureBeforeBatch} right before the write
 * happens, and (for UPDATE — CREATE already gets its "after" from the method's return value)
 * {@link #captureAfter}/{@link #captureAfterBatch} right after it, typically by re-reading the
 * row or reconstructing it from the applied patch; {@link AuditLogAspect} consumes and clears
 * the entries after the method returns.
 *
 * <p>Storage is keyed first by <b>call scope</b> (one per {@code @Audited} invocation, pushed by
 * the aspect before {@code proceed()} and popped in its {@code finally}), then by
 * {@code String.valueOf(id)} within that scope. A flat id-only map was tried first and had a real
 * bug: two <i>different</i> {@code @Audited} methods can legitimately target the same entity id
 * in one request (e.g. deleting an Account also deletes its account_currency rows, both keyed by
 * the account id) — the inner call's consume silently stole the outer call's captured snapshot,
 * and the inner call's cleanup wiped the entire thread-local, collateral-damaging the outer call's
 * still-pending data. Scoping by call fixes both: nested calls can never see each other's entries,
 * and each call only ever clears its own scope.
 */
public final class AuditContext {

    private static final ThreadLocal<Map<String, Map<String, Object>>> BEFORE = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<String, Map<String, Object>>> AFTER = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<String, Map<String, Object>>> SUMMARY = ThreadLocal.withInitial(HashMap::new);

    /** LIFO stack of call-scope ids — captureBefore/captureAfter always write into the top (the call currently executing). */
    private static final ThreadLocal<Deque<String>> CALL_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private AuditContext() {
    }

    /** Called only by {@link AuditLogAspect}, once per {@code @Audited} invocation, before {@code proceed()}. */
    static String pushCallScope() {
        String scope = UUID.randomUUID().toString();
        CALL_STACK.get().push(scope);
        return scope;
    }

    /**
     * Called only by {@link AuditLogAspect} in a {@code finally} block, after this call's own
     * rows (if any) are recorded — pops and clears only this call's own scope, never a caller's
     * or callee's. Push/pop always nest correctly (every {@code @Audited} invocation is wrapped
     * in its own try/finally), so the top of the stack here is guaranteed to be this call's own.
     */
    static void popCallScope() {
        Deque<String> stack = CALL_STACK.get();
        String scope = stack.isEmpty() ? null : stack.pop();
        if (scope != null) {
            BEFORE.get().remove(scope);
            AFTER.get().remove(scope);
            SUMMARY.get().remove(scope);
        }
        if (stack.isEmpty()) {
            BEFORE.remove();
            AFTER.remove();
            SUMMARY.remove();
            CALL_STACK.remove();
        }
    }

    public static void captureBefore(Object id, Object snapshot) {
        if (id == null) {
            return;
        }
        String scope = currentScope();
        if (scope == null) {
            return;
        }
        BEFORE.get().computeIfAbsent(scope, k -> new HashMap<>()).put(String.valueOf(id), snapshot);
    }

    public static void captureBeforeBatch(Map<?, ?> idToSnapshot) {
        if (idToSnapshot == null) {
            return;
        }
        idToSnapshot.forEach((id, snapshot) -> captureBefore(id, snapshot));
    }

    /** For UPDATE (and any other non-CREATE action) methods that want a real "operation后" snapshot in the audit row. */
    public static void captureAfter(Object id, Object snapshot) {
        if (id == null) {
            return;
        }
        String scope = currentScope();
        if (scope == null) {
            return;
        }
        AFTER.get().computeIfAbsent(scope, k -> new HashMap<>()).put(String.valueOf(id), snapshot);
    }

    public static void captureAfterBatch(Map<?, ?> idToSnapshot) {
        if (idToSnapshot == null) {
            return;
        }
        idToSnapshot.forEach((id, snapshot) -> captureAfter(id, snapshot));
    }

    /** Called only by {@link AuditLogAspect} with the call scope it pushed itself, never another call's. */
    static Object consumeBefore(String scope, Object id) {
        if (scope == null || id == null) {
            return null;
        }
        Map<String, Object> byId = BEFORE.get().get(scope);
        return byId == null ? null : byId.remove(String.valueOf(id));
    }

    static Object consumeAfter(String scope, Object id) {
        if (scope == null || id == null) {
            return null;
        }
        Map<String, Object> byId = AFTER.get().get(scope);
        return byId == null ? null : byId.remove(String.valueOf(id));
    }

    /**
     * Stages a human-readable {@code audit_log.summary} sentence for one entity id — only the
     * method body has the context (resolved account names, currency codes, etc.) to write one
     * that reads naturally; {@link AuditLogAspect} consumes it the same way as
     * {@link #captureBefore}/{@link #captureAfter}. Callers write the "core" sentence only —
     * no trailing company name — {@code AuditLogServiceImpl.record()} appends that centrally.
     * When nothing is staged, {@link AuditSummaryDefaults} builds a generic fallback instead.
     */
    public static void captureSummary(Object id, String summary) {
        if (id == null) {
            return;
        }
        String scope = currentScope();
        if (scope == null) {
            return;
        }
        SUMMARY.get().computeIfAbsent(scope, k -> new HashMap<>()).put(String.valueOf(id), summary);
    }

    static Object consumeSummary(String scope, Object id) {
        if (scope == null || id == null) {
            return null;
        }
        Map<String, Object> byId = SUMMARY.get().get(scope);
        return byId == null ? null : byId.remove(String.valueOf(id));
    }

    private static String currentScope() {
        Deque<String> stack = CALL_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }
}
