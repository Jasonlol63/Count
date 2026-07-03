package com.eazycount.dao;

import com.eazycount.dto.AutoRenewDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AutoRenewDao {

    void syncWindowRequests(@Param("windowDays") int windowDays);

    List<AutoRenewDTO> selectAutoRenewList(@Param("status") String status, @Param("tenantType") String tenantType, @Param("dateFrom") LocalDate dateFrom,
                                           @Param("dateTo") LocalDate dateTo, @Param("windowDays") int windowDays);

    AutoRenewDTO selectRequestById(@Param("requestId") Integer requestId);

    int countRequestsByStatus(@Param("status") String status, @Param("tenantType") String tenantType, @Param("windowDays") int windowDays);

    int countPendingByTenantType(@Param("tenantType") String tenantType, @Param("windowDays") int windowDays);

    void approveRequest(@Param("requestId") Integer requestId, @Param("newExpirationDate") LocalDate newExpirationDate, @Param("processedBy") String processedBy);

    void updateTenantExpiration(@Param("tenantId") Integer tenantId, @Param("newExpirationDate") LocalDate newExpirationDate);

    void rejectRequest(@Param("requestId") Integer requestId, @Param("processedBy") String processedBy);
}
