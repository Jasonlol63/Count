package com.eazycount.entity;

import lombok.*;

import java.time.LocalDateTime;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Process {
    private Integer id;
    private Integer tenantId;
    private String code;
    private Integer currencyId;
    private String currencyCode; // 🌟 新增：用于存放关联查询出来的币种 Code (如 MYR, SGD)
    // 对应 description_ids JSON 字段
    private String descriptionIds;
    // 对应 schedule_days JSON 字段
    private String scheduleDays;
    // 对应 settings JSON 字段
    private String settings;
    private String remark;
    private Status status;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public enum Status {
        ACTIVE,
        INACTIVE
    }
}
