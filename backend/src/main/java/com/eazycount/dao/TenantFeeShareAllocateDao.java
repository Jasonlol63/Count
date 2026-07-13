package com.eazycount.dao;

import com.eazycount.entity.TenantFeeShareAllocate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TenantFeeShareAllocateDao {

    List<TenantFeeShareAllocate> findFeeShareByTenantId(@Param("tenantId") Integer tenantId);

    void deleteByTenantId(@Param("tenantId") Integer tenantId);

    void batchInsert(@Param("list") List<TenantFeeShareAllocate> list);
}
