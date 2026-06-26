package com.eazycount.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps to the {@code account} table (Member tab login).
 * {@code role} is the ledger role (CAPITAL, AGENT, DEBTOR, ...); session user_type is always member.
 */
@Getter
@Setter
@ToString(exclude = "password")
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Integer id;

    private String accountId;

    private String name;

    private String password;

    /** Ledger role: CAPITAL, BANK, AGENT, MEMBER, DEBTOR, ... */
    private String role;

    private AccountStatus status;

    private String createdSource;

    private Integer paymentAlert;

    private String alertDay;

    private BigDecimal alertAmount;

    private Date alertSpecificDate;

    private String remark;

    private LocalDateTime lastLogin;

    private LocalDateTime createdAt;

    @Getter
    public enum AccountStatus {
        ACTIVE("ACTIVE"),
        INACTIVE("INACTIVE");

        private final String value;

        AccountStatus(String value) {
            this.value = value;
        }

        public static AccountStatus fromValue(String value) {
            if (value == null) {
                return null;
            }

            final String normalized = value.trim();
            for (AccountStatus status : values()) {
                if (status.name().equalsIgnoreCase(normalized) || status.value.equalsIgnoreCase(normalized)) {
                    return status;
                }
            }

            throw new IllegalArgumentException("Unknown status: " + value);
        }
    }
}
