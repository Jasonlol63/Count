package com.eazycount.dao;

import com.eazycount.dto.CustomerReportDTO;
import com.eazycount.dto.DomainReportDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ReportDao {

    // Customer Report: one row per (account, account_currency) under the tenant, left-joined to its
    // Data-Capture-originated WIN/LOSE transactions (bank_process_posted_id IS NULL) for the date range.
    // Accounts with no matching transactions still come back with 0/0 totals for each assigned currency.
    List<CustomerReportDTO> findCustomerReportRows(
            @Param("tenantId") Integer tenantId,
            @Param("currencyCodes") List<String> currencyCodes,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("accountId") Integer accountId);

    // Domain Report: one row per GAME-category process under the tenant, left-joined to its
    // Data-Capture-originated WIN/LOSE transactions (bank_process_posted_id IS NULL) for the date
    // range. process is the driving table — processes with no matching transactions still come back
    // with 0/0 totals. No currency dimension, no Show All (always every process).
    List<DomainReportDTO> findDomainReportRows(
            @Param("tenantId") Integer tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("processId") Integer processId,
            @Param("category") String category);
}
