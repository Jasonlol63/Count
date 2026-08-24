package com.eazycount.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Announcements {
    private Integer id;
    private String title;
    private String content;
    private String companyCode;
    private Status status;
    private Integer createdBy;
    private User userType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @NoArgsConstructor
    public enum Status {
        ACTIVE,
        INACTIVE;
    }

    public enum User {
        USER,
        OWNER;
    }
}
