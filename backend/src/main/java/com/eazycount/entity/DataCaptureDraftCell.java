package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code data_capture_draft_cell} — one non-empty BANK draft cell (no JSON).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureDraftCell {

    private Long id;

    private Integer draftId;

    private Integer rowIndex;

    private Integer colIndex;

    private String cellValue;

    private LocalDateTime updatedAt;
}
