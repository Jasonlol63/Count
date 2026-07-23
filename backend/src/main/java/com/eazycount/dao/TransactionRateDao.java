package com.eazycount.dao;

import com.eazycount.entity.TransactionRate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TransactionRateDao {

    void insert(TransactionRate row);

    TransactionRate findByTenantIdAndRateGroupId(
            @Param("tenantId") Integer tenantId,
            @Param("rateGroupId") String rateGroupId);
}
