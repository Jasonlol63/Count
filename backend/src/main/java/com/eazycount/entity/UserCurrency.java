package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCurrency {
    private Integer id;
    private Integer accountId;
    private Integer tenantId;
    private Integer currencyId;
    private Integer sortOrder;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
}
