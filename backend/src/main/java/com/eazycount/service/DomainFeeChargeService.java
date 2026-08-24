package com.eazycount.service;

import com.eazycount.entity.Tenant;
import com.eazycount.entity.Transaction;

import java.util.List;

public interface DomainFeeChargeService {

    List<Transaction> chargeDomainFeeIfRequested(Tenant tenant);

    List<Transaction> chargeDomainFee(Tenant tenant, String period);
}
