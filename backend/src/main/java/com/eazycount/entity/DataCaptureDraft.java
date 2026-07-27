package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code data_capture_draft} — BANK Data Capture grid draft header (TEXT only).
 * Cells are in {@link DataCaptureDraftCell}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureDraft {

    private Integer id;

    private Integer tenantId;

    private Integer processId;

    private Integer currencyId;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
