package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    private String createdBy;

    private LocalDateTime createAt;

    private List<String> descriptionNames;

    private List<DataCaptureGameDTO> processes;

    private DataCaptureGameDTO selectedProcess;
}
