package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code process} — process config (settings as columns; description/days via link tables).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Process {

    private Integer id;

    private Integer tenantId;

    private String code;

    private Integer currencyId;

    private String removeWord;

    private String replaceWordFrom;

    private String replaceWordTo;

    private String remark;

    private Status status;

    /** Creator login_id (admin {@code user.login_id} or owner {@code owner_code}) */
    private String createdBy;

    /** Last editor login_id */
    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    public enum Status {
        ACTIVE,
        INACTIVE
    }
}
