package com.eazycount.dao;

import com.eazycount.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransactionDao {

    void insert(Transaction row);
}
