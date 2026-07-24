package com.eazycount.dao;

import com.eazycount.entity.TransactionRate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TransactionRateDao {

    void insert(TransactionRate row);

    TransactionRate findByTenantIdAndRateGroupId(
            @Param("tenantId") Integer tenantId,
            @Param("rateGroupId") String rateGroupId);

    /** Remove RATE headers before deleting linked {@code transactions} rows (FK on leg1/leg2). */
    int deleteByTenantIdAndRateGroupIds(
            @Param("tenantId") Integer tenantId,
            @Param("rateGroupIds") List<String> rateGroupIds);
}
