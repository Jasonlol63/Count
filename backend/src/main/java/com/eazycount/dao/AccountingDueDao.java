package com.eazycount.dao;

import com.eazycount.entity.BkProcessAccountingPosted;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AccountingDueDao {

    List<BkProcessAccountingPosted> findSettledPeriods(@Param("tenantId") Integer tenantId,
                                                       @Param("fromDate") LocalDate fromDate,
                                                       @Param("toDate") LocalDate toDate);

    BkProcessAccountingPosted findLedgerEntry(@Param("tenantId") Integer tenantId, @Param("bankProcessId") Integer bankProcessId, @Param("postedDate") LocalDate postedDate, @Param("periodType") BkProcessAccountingPosted.PeriodType periodType);

    void insertLedgerEntry(BkProcessAccountingPosted row);

    int deleteSkippedInRange(@Param("tenantId") Integer tenantId, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
}
