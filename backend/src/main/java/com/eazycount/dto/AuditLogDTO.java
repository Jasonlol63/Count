package com.eazycount.dto;

import com.eazycount.entity.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Flat DTO for the IT audit-log console — one class covers list/summary query filters,
 * {@code record()} call-site parameters, and query results, same flattening style as
 * {@link BankProcessDTO}. See docs/it-role-audit-log.md.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDTO {

    /* search()/summary(): filter fields. */
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String tenantCode;
    private String keyword;
    private Integer offset;
    private Integer limit;

    /* Shared by both the search()/summary() filters (module/action to filter by) and
     * record() (module/action being recorded) — same meaning either way, no need for
     * separate fields. */
    private String module;
    private AuditLog.Action action;

    /* record(): call-site parameters. operator/tenant are filled by the service from the
     * current session, not by the caller. */
    private String entityId;
    private String sourceTable;
    private String summary;
    /** Serialized to JSON text by the service; pass a Map/DTO with DB column-name keys, or null. */
    private Object beforeData;
    private Object afterData;
    private boolean restorable;
    /** Set only for action=RESTORE — points back at the DELETE row being restored. */
    private Long relatedLogId;

    /* search(): result fields. */
    private List<AuditLog> items;
    private long total;

    /* summary(): result fields — primitive long, not Long: SUM() over zero matching rows is
     * NULL in SQL, but the mapper's IFNULL(...) guards against that (see AuditLogMapper.xml). */
    private long createCount;
    private long updateCount;
    private long deleteCount;
    private long restorableCount;
    private long restoredCount;
}
