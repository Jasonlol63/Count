package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to the {@code account_link} table (links between member accounts).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserLink {

    private Long id;

    /** FK account.id (smaller ID) */
    private Integer accountId1;

    /** FK account.id (larger ID) */
    private Integer accountId2;

    /** FK tenant.id */
    private Integer tenantId;

    private LinkType linkType;

    /** For unidirectional, defines the link source account.id */
    private Integer sourceAccountId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum LinkType {
        BIDIRECTIONAL,
        UNIDIRECTIONAL
    }
}
