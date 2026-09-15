package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    private Long id;
    private String operatorId;
    private String operatorName;
    private String operatorRole;
    private Integer tenantId;
    private String tenantCode;
    private String module;
    private Action action;
    private String entityId;
    private String sourceTable;
    private String summary;
    private String beforeData;
    private String afterData;
    private Boolean restorable;
    private Boolean restored;
    private String restoredBy;
    private LocalDateTime restoredAt;
    private Long relatedLogId;
    private LocalDateTime createdAt;

    public enum Action {
        CREATE,
        UPDATE,
        DELETE,
        RESTORE;
    }
}
