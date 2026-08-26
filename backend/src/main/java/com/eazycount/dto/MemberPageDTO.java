package com.eazycount.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MemberPageDTO {
    private Integer accountId;
    private String accountCode;
    private String accountName;

    private Integer tenantId;
    private String tenantCode;
    private String tenantName;

    private boolean hasAccountLink;

    // This account's own selected currencies — populated only when hasAccountLink is false.
    private List<UserCurrencyDTO> currencies;

    // Self + every account visible via Account Link — populated only when hasAccountLink is true.
    private List<LinkedAccount> linkedAccounts;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedAccount {
        private Integer id;
        private String accountCode;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchRequest {
        private List<Integer> accountIds;
        private List<String> currencyCodes;
        private String dateFrom;
        private String dateTo;
    }

    //One account's own currencies — one entry of the batch response for the Member mini grid.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountCurrencies {
        private Integer accountId;
        private List<UserCurrencyDTO> currencies;
    }

    // One (account, currency) closing-balance cell for the Member mini grid.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountCurrencyBalance {
        private Integer accountId;
        private String currency;
        private String balance;
    }
}
