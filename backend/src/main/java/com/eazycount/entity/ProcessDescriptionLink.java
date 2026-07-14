package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Maps to {@code process_description_link} — process ↔ description many-to-many
 * (replaces {@code process.description_ids} JSON).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDescriptionLink {

    private Integer id;

    private Integer processId;

    private Integer descriptionId;

    private LocalDateTime createdAt;
}
