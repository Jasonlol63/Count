package com.eazycount.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BankCountry {

    private Integer id;

    private Integer tenantId;

    //Country code e.g. MYR, SGD, AUD
    private String code;

    private LocalDateTime createdAt;
}
