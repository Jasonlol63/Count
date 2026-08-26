package com.eazycount.dao;

import com.eazycount.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TransactionDao {

    void insert(Transaction row);

    // Delete-process guard: count transactions.rows recorded under this process's data captures
    int countTransactionsByProcessId(@Param("processId") Integer processId, @Param("tenantId") Integer tenantId);

    // Delete-account guard: count transactions referencing this account as either account_id (payer) or from_account_id (transfer source).
    int countTransactionsByAccountId(@Param("accountId") Integer accountId, @Param("tenantId") Integer tenantId);

    // Delete-currency guard: count transactions recorded under this currency. currency_id is ON DELETE
    // SET NULL, so without this check deleting a currency silently blanks out historical transactions'
    // currency context.
    int countTransactionsByCurrencyId(@Param("currencyId") Integer currencyId, @Param("tenantId") Integer tenantId);
}
