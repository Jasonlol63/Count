package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Capture Maintenance row (list only for now — delete not yet implemented):
 * one {@code data_capture_line} per row, MAIN+SUB, one category (GAME/BANK) at a time.
 */
@Getter
@Setter
public class MaintenanceCaptureDTO {

    private Integer tenantId;

    private String dateFrom;
    private String dateTo;
    private String process;
    private String category;
    private String q;

    private Integer id;
    private LocalDateTime dtsCreated;
    private String product;
    private String currency;
    private String wlGroup;
    private String createdBy;

    private Boolean deleted;
    private String deletedBy;
    private LocalDateTime deletedAt;
}
