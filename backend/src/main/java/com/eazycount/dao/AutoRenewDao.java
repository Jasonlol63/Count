package com.eazycount.dao;

import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.AutoRenewTransactionDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AutoRenewDao {

    void syncWindowRequests(@Param("windowDays") int windowDays);

    List<AutoRenewDTO> selectAutoRenewList(@Param("status") String status, @Param("tenantType") String tenantType, @Param("dateFrom") LocalDate dateFrom,
                                           @Param("dateTo") LocalDate dateTo, @Param("windowDays") int windowDays);

    AutoRenewDTO selectRequestById(@Param("requestId") Integer requestId);

    int countRequestsByStatus(@Param("status") String status, @Param("tenantType") String tenantType, @Param("windowDays") int windowDays,
                               @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    int countPendingByTenantType(@Param("tenantType") String tenantType, @Param("windowDays") int windowDays);

    void approveRequest(
            @Param("requestId") Integer requestId,
            @Param("period") String period,
            @Param("price") BigDecimal price,
            @Param("newExpirationDate") LocalDate newExpirationDate,
            @Param("processedBy") String processedBy);

    void updateTenantExpiration(@Param("tenantId") Integer tenantId, @Param("newExpirationDate") LocalDate newExpirationDate);

    void rejectRequest(@Param("requestId") Integer requestId, @Param("processedBy") String processedBy);

    void insertRequestTransactionLink(@Param("requestId") Integer requestId, @Param("transactionId") Integer transactionId);

    List<AutoRenewTransactionDTO> selectTransactionLinksByRequestId(@Param("requestId") Integer requestId);

    void deleteTransactionsByIds(@Param("transactionIds") List<Integer> transactionIds);

    void revertApprovedToPending(@Param("requestId") Integer requestId);

    void revertRejectedToPending(@Param("requestId") Integer requestId);
}
