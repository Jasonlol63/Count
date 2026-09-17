package com.eazycount.audit;

import com.eazycount.dto.AuditLogDTO;
import com.eazycount.entity.AuditLog;
import com.eazycount.service.AuditLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Auto-records {@code audit_log} rows for methods annotated {@link Audited} — see
 * docs/it-role-audit-log.md. Only records on successful completion (exceptions propagate
 * normally, untouched, and produce no log row).
 *
 * <p>A single method call can affect several entities (e.g. a batch delete) — {@link Audited#entityIdExpr()}
 * may resolve to a collection, in which case one audit_log row is written per id, matching the
 * IT console's one-row-per-entity design.
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    private static final ExpressionParser SPEL = new SpelExpressionParser();
    // ASM-based local-variable-table discovery as a fallback — works even without the
    // -parameters compiler flag, unlike relying on MethodSignature.getParameterNames() alone.
    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    @Autowired
    private AuditLogService auditLogService;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            recordAll(joinPoint, audited, result);
        } catch (Exception e) {
            // A broken audit capture must never look like the underlying write failed —
            // to write already succeeded by the time we get here.
            log.warn("AuditLogAspect failed to record audit for {}", joinPoint.getSignature(), e);
        } finally {
            AuditContext.clear();
        }

        return result;
    }

    private void recordAll(ProceedingJoinPoint joinPoint, Audited audited, Object result) {
        List<Object> ids = toIdList(evaluateEntityIdExpr(joinPoint, audited.entityIdExpr(), result));
        if (ids.isEmpty()) {
            log.warn("@Audited({}) on {} resolved no entity ids — nothing recorded",
                    audited.module(), joinPoint.getSignature());
            return;
        }

        for (Object id : ids) {
            AuditLogDTO request = new AuditLogDTO();
            request.setModule(audited.module());
            request.setAction(audited.action());
            request.setEntityId(String.valueOf(id));
            request.setSourceTable(audited.sourceTable());
            request.setRestorable(audited.restorable());
            request.setBeforeData(AuditContext.consumeBefore(id));
            request.setAfterData(resolveAfterData(id, audited.action(), result));
            auditLogService.record(request);
        }
    }

    /**
     * CREATE keeps its long-standing behavior of using the method's return value as "after"
     * (no method needs to opt in). For every other action, "after" only exists if the method
     * body explicitly staged one via {@link AuditContext#captureAfter}/{@code captureAfterBatch}
     * — most don't yet, and their audit rows correctly keep {@code afterData == null} until they do.
     */
    private Object resolveAfterData(Object id, AuditLog.Action action, Object result) {
        Object captured = AuditContext.consumeAfter(id);
        if (captured != null) {
            return captured;
        }
        return action == AuditLog.Action.CREATE ? result : null;
    }

    /**
     * Binds method parameters by name (e.g. {@code #request}) AND the method's return value as
     * {@code #result} — plain SpEL doesn't wire up {@code #result} automatically the way
     * {@code @AfterReturning(returning=...)}/{@code @Cacheable} do; we bind it ourselves since
     * {@link Audited#entityIdExpr()} needs to reference either depending on the operation
     * (e.g. a DELETE reads the actually-deleted ids from the return value, not the request).
     */
    private Object evaluateEntityIdExpr(ProceedingJoinPoint joinPoint, String expr, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] paramNames = PARAM_NAMES.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("result", result);

        Expression expression = SPEL.parseExpression(expr);
        return expression.getValue(context);
    }

    private List<Object> toIdList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> ids = new ArrayList<>();
            for (Object item : collection) {
                if (item != null) {
                    ids.add(item);
                }
            }
            return ids;
        }
        return List.of(value);
    }
}
