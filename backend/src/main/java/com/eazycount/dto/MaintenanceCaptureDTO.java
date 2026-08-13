package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Capture Maintenance row: one {@code data_captures} header per row (all its data_capture_line rows
 * rolled up into it — one submission may cover several products), one category (GAME/BANK) at a time.
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

    // Delete request: selected data_captures.id values from the list checkboxes
    private List<Integer> captureIds;

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
