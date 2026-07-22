package com.eazycount.service;

import com.eazycount.entity.Tenant;

/**
 * Domain Confirm "Charge on Save" / Auto Renew Approve — posts the Domain Fee for one paying tenant
 * (company/group) to the C168 ledger, split by its saved {@code tenant_fee_share_allocation}
 * (Sales/CS/IT commission + C168 Profit remainder).
 */
public interface DomainFeeChargeService {

    /**
     * No-op (returns 0) unless {@code tenant.getChargeDomainFeeOnConfirm()} is true.
     * Reads the already-persisted Share % allocation for {@code tenant.getId()} —
     * independent of whatever allocation rows were included on this same request.
     *
     * @return number of {@code transactions} rows inserted (0 if not charged).
     */
    int chargeDomainFeeIfRequested(Tenant tenant);

    /**
     * Always posts Domain Fee for {@code period} using the payer tenant's saved Share %.
     * Used by Auto Renew Approve (no Charge-on-Save toggle).
     *
     * @return number of {@code transactions} rows inserted
     */
    int chargeDomainFee(Tenant tenant, String period);
}
