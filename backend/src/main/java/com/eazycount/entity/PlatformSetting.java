package com.eazycount.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSetting {

    private int id;
    private String telegramSupportLink;
    private String updatedBy;
    private UpdatedByType updatedByType;
    private LocalDateTime updatedAt;

    public enum UpdatedByType{
        USER,
        OWNER
    }
}
