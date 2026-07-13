package com.eazycount.dao;

import com.eazycount.entity.DomainListFeePrice;
import com.eazycount.entity.RenewalPeriod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DomainListFeePriceDao {

    List<DomainListFeePrice> findAll();

    List<RenewalPeriod> findAllRenewalPeriodsOrdered();

    void insertDefaults();

    void batchUpsert(@Param("list") List<DomainListFeePrice> list);
}
