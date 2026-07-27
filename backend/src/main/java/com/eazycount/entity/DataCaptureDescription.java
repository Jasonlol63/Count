package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code data_capture_description} — multi-select description snapshot
 * for one GAME {@link DataCapture} (allowed list remains {@code process_description_link}).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DataCaptureDescription {

    private Integer id;

    private Integer captureId;

    private Integer descriptionId;

    private LocalDateTime createdAt;
}
