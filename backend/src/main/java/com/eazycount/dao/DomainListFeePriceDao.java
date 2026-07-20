package com.eazycount.dao;

import com.eazycount.entity.DomainListFeePrice;
import com.eazycount.entity.RenewalPeriod;
import com.eazycount.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface DomainListFeePriceDao {

    List<DomainListFeePrice> findAll();

    List<RenewalPeriod> findAllRenewalPeriodsOrdered();

    void insertDefaults();

    void batchUpsert(@Param("list") List<DomainListFeePrice> list);

    /** Domain Fee price for one tenant type + period — used by Domain Confirm charge. */
    BigDecimal findPriceByTenantTypeAndPeriod(
            @Param("tenantType") Tenant.TenantType tenantType, @Param("period") String period);
}
