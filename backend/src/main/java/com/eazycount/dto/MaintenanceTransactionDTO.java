package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Transaction Maintenance row (view-only): one {@code data_capture_line} per row, MAIN+SUB, all processes. */
@Getter
@Setter
public class MaintenanceTransactionDTO {

    private Integer tenantId;

    private String dateFrom;
    private String dateTo;
    private String process;
    private String category;
    private String q;

    private Integer id;
    private LocalDateTime dtsCreated;
    private String idProduct;
    private String account;
    private String description;
    private String remark;
    private String percent;
    private String currency;
    private String rate;
    private BigDecimal cr;
    private BigDecimal dr;
    private String createdBy;

    private Boolean deleted;
    private String deletedBy;
    private LocalDateTime deletedAt;
}
