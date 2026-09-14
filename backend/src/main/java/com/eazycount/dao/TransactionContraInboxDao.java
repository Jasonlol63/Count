package com.eazycount.dao;

import com.eazycount.dto.TransactionContraInboxDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Contra Inbox (list/approve/reject PENDING manual transactions); backs {@code ContraInboxServiceImpl}. */
@Mapper
public interface TransactionContraInboxDao {

    // PENDING manual transactions for this tenant, oldest transaction_date first.
    List<TransactionContraInboxDTO> findPendingRows(@Param("tenantId") Integer tenantId);

    // Approve: PENDING -> APPROVED in place (same row/id, no archive). Returns 0 if not found/not PENDING.
    int approvePendingTransaction(
            @Param("tenantId") Integer tenantId,
            @Param("id") Integer id,
            @Param("approvedBy") String approvedBy);

    // Reject step 1: archive the still-PENDING row into transactions_deleted as REJECTED.
    // Caller then hard-deletes it via MaintenanceDao.deleteByIdsAndTenantId (same archive-then-delete
    // pattern as Payment/Bank Process Maintenance). Returns 0 if not found/not PENDING.
    int archiveRejectedToDeleted(
            @Param("tenantId") Integer tenantId,
            @Param("id") Integer id,
            @Param("rejectedBy") String rejectedBy);
}
