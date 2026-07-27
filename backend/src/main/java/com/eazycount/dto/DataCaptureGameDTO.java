package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Games Data Capture — single DTO for request, process option, detail, and combined response.
 * <ul>
 *   <li>Request: {@code tenantId}, {@code captureDate}, optional {@code id} (process pk)</li>
 *   <li>Option row: {@code id}, {@code processId}, {@code descriptionName}, {@code processDisplay}</li>
 *   <li>Detail row: currency / word / remark / {@code descriptionNames}</li>
 *   <li>Response: {@code processes}, optional {@code selectedProcess}</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureGameDTO {

    private Integer tenantId;

    private LocalDate captureDate;

    private Integer id;

    private String processId;

    private String descriptionName;

    private String processDisplay;

    private Integer currencyId;

    private String currencyCode;

    private String removeWord;

    private String replaceWordFrom;

    private String replaceWordTo;

    private String remark;

    private List<String> descriptionNames;

    private List<DataCaptureGameDTO> processes;

    private DataCaptureGameDTO selectedProcess;
}
