package com.eazycount.service;

import java.util.Map;

public interface AutoRenewService {

    Map<String, Object> getAutoRenewCounts(String tenantType, int windowDays);

    Map<String, Object> getAutoRenewList(String status, String tenantType, String dateFromStr, String dateToStr);

    void rejectRequest(Integer requestId);
}
