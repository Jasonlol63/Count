package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to {@code data_captures} — Data Capture submit header (GAME / BANK).
 * Selected GAME descriptions are in {@link DataCaptureDescription}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DataCapture {

    private Integer id;

    private Integer tenantId;

    private Category category;

    private LocalDate captureDate;

    private Integer processId;

    private Integer currencyId;

    private String remark;

    private String removeWord;

    private String replaceWordFrom;

    private String replaceWordTo;

    private String createdBy;

    private LocalDateTime createdAt;

    @Getter
    public enum Category {
        GAME,
        BANK
    }
}
