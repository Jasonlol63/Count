package com.eazycount.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AutoRenew {
    private Integer id;
    private Integer tenantId;
    private String period;
    private BigDecimal price;
    private LocalDateTime expirationSnapshot;
    private LocalDateTime newExpirationDate;
    private String processBy;
    private LocalDateTime processAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private enum Status{
        PENDING,
        APPROVED,
        REJECTED
    }
}
