package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to {@code process_day} — run weekdays for a process
 * (replaces {@code process.schedule_days} JSON).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDay {

    private Integer id;

    private Integer processId;

    private Integer dayOfWeek;
}
