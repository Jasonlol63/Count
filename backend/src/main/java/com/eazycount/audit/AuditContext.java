package com.eazycount.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local staging area for "before" snapshots that only the annotated method's own body
 * can see (an AOP aspect wrapping the method from the outside has no visibility into local
 * variables). Call {@link #captureBefore}/{@link #captureBeforeBatch} right before the write
 * happens; {@link AuditLogAspect} consumes and clears the entries after the method returns.
 *
 * <p>Keyed by {@code String.valueOf(id)} so callers don't need to worry about Integer vs int
 * vs String id types matching exactly.
 */
public final class AuditContext {

    private static final ThreadLocal<Map<String, Object>> BEFORE = ThreadLocal.withInitial(HashMap::new);

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

    static Object consumeBefore(Object id) {
        return id == null ? null : BEFORE.get().remove(String.valueOf(id));
    }

    /** Called by the aspect in a finally block — thread-pooled servers must not leak entries across requests. */
    static void clear() {
        BEFORE.remove();
    }
}
