package com.eazycount.dto;

import com.eazycount.entity.Currency;
import com.eazycount.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCurrencyDTO {
    private User user;
    private Currency currency;
}
