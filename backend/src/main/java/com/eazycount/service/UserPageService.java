package com.eazycount.service;

import com.eazycount.dto.MemberPageDTO;
import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;
import com.eazycount.dto.UserCurrencyDTO;

import java.util.List;

public interface UserPageService {

    MemberPageDTO getMemberProfile();

    TransactionHistoryResult getMemberHistory(TransactionHistoryRequest request);

    List<UserCurrencyDTO> getMemberAccountCurrencies(Integer accountId);

    List<MemberPageDTO.AccountCurrencies> getMemberAccountsCurrencies(List<Integer> accountIds);

    List<MemberPageDTO.AccountCurrencyBalance> getMemberMiniGridBalances(MemberPageDTO.BatchRequest request);
}
