package com.eazycount.dao;

import com.eazycount.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;

/**
 * Base {@code transactions} table write path — shared by every write flow (Bank Process posting,
 * Domain Fee, manual Transaction submit, Data Capture Summary Submit). Read-side query methods live
 * in {@link TransactionSearchDao} / {@link TransactionHistoryDao} / {@link MaintenanceDao},
 * split out per consuming service (see docs/transaction-datacapture-winloss.md).
 */
@Mapper
public interface TransactionDao {

    void insert(Transaction row);
}
