package com.eazycount.service;

import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.AutoRenewListResponseDTO;

import java.time.LocalDate;

public interface AutoRenewService {

    AutoRenewDTO getAutoRenewCounts(String tenantType, int windowDays, LocalDate dateFrom, LocalDate dateTo);

    AutoRenewListResponseDTO getAutoRenewList(String status, String tenantType, String dateFromStr, String dateToStr);

    void rejectRequest(Integer requestId);

    AutoRenewDTO approveRequest(Integer requestId, String period);

    void deleteRequest(Integer requestId);
}
