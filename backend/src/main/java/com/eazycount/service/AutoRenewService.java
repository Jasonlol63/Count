package com.eazycount.service;

import java.util.Map;

public interface AutoRenewService {

    Map<String, Object> getAutoRenewCounts(String tenantType, int windowDays);

    Map<String, Object> getAutoRenewList(String status, String tenantType, String dateFromStr, String dateToStr);

    void rejectRequest(Integer requestId);

    /**
     * Approve a pending auto-renew request: post Domain Fee (same as Domain Charge on Save)
     * using saved Share %, then extend tenant expiration from current expiration + period.
     */
    Map<String, Object> approveRequest(Integer requestId, String period);
}
