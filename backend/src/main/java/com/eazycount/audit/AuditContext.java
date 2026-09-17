package com.eazycount.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local staging area for "before"/"after" snapshots that only the annotated method's own
 * body can see (an AOP aspect wrapping the method from the outside has no visibility into local
 * variables). Call {@link #captureBefore}/{@link #captureBeforeBatch} right before the write
 * happens, and (for UPDATE — CREATE already gets its "after" from the method's return value)
 * {@link #captureAfter}/{@link #captureAfterBatch} right after it, typically by re-reading the
 * row or reconstructing it from the applied patch; {@link AuditLogAspect} consumes and clears
 * the entries after the method returns.
 *
 * <p>Keyed by {@code String.valueOf(id)} so callers don't need to worry about Integer vs int
 * vs String id types matching exactly.
 */
public final class AuditContext {

    private static final ThreadLocal<Map<String, Object>> BEFORE = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<String, Object>> AFTER = ThreadLocal.withInitial(HashMap::new);

    private AuditContext() {
    }

    public static void captureBefore(Object id, Object snapshot) {
        if (id == null) {
            return;
        }
        BEFORE.get().put(String.valueOf(id), snapshot);
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
        AFTER.get().put(String.valueOf(id), snapshot);
    }

    public static void captureAfterBatch(Map<?, ?> idToSnapshot) {
        if (idToSnapshot == null) {
            return;
        }
        idToSnapshot.forEach((id, snapshot) -> captureAfter(id, snapshot));
    }

    static Object consumeBefore(Object id) {
        return id == null ? null : BEFORE.get().remove(String.valueOf(id));
    }

    static Object consumeAfter(Object id) {
        return id == null ? null : AFTER.get().remove(String.valueOf(id));
    }

    /** Called by the aspect in a finally block — thread-pooled servers must not leak entries across requests. */
    static void clear() {
        BEFORE.remove();
        AFTER.remove();
    }
}
