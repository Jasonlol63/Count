package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.AuditSnapshots;
import com.eazycount.audit.Audited;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.TransactionContraInboxDao;
import com.eazycount.dao.MaintenanceDao;
import com.eazycount.dto.TransactionContraInboxDTO;
import com.eazycount.entity.AuditLog;
import com.eazycount.entity.Transaction;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionContraInboxService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionContraInboxServiceImpl implements TransactionContraInboxService {

    @Autowired
    private TransactionContraInboxDao transactionContraInboxDao;

    @Autowired
    private MaintenanceDao maintenanceDao;

    @Override
    public List<TransactionContraInboxDTO> listPending(Integer tenantId) {
        requireApproverSession();
        AccessControlUtils.requireValidTenantId(tenantId);
        return transactionContraInboxDao.findPendingRows(tenantId);
    }

    @Override
    @Audited(module = "TRANSACTION_CONTRA_INBOX", action = AuditLog.Action.UPDATE, entityIdExpr = "#request.id", sourceTable = "transactions")
    @Transactional
    public void approve(TransactionContraInboxDTO request) {
        SessionUser session = requireApproverSession();
        Integer tenantId = request != null ? request.getTenantId() : null;
        AccessControlUtils.requireValidTenantId(tenantId);
        int id = requireId(request != null ? request.getId() : null);

        Transaction before = findByIdOrNull(tenantId, id);
        AuditContext.captureBefore(id, AuditSnapshots.transaction(before));

        int updated = transactionContraInboxDao.approvePendingTransaction(tenantId, id, session.login_id.trim());
        if (updated <= 0) {
            throw new BusinessException("Transaction is no longer pending approval");
        }

        Transaction after = findByIdOrNull(tenantId, id);
        AuditContext.captureAfter(id, AuditSnapshots.transaction(after));
    }

    // Reject archives the PENDING row into transactions_deleted then hard-deletes it from `transactions`
    // (same archive-then-delete pattern as Payment/Bank Process Maintenance) — the row no longer exists
    // afterward, so this is audited as a DELETE with only a before snapshot, not an in-place UPDATE.
    @Override
    @Audited(module = "TRANSACTION_CONTRA_INBOX", action = AuditLog.Action.DELETE, entityIdExpr = "#request.id", sourceTable = "transactions")
    @Transactional
    public void reject(TransactionContraInboxDTO request) {
        SessionUser session = requireApproverSession();
        Integer tenantId = request != null ? request.getTenantId() : null;
        AccessControlUtils.requireValidTenantId(tenantId);
        int id = requireId(request != null ? request.getId() : null);
        String rejectedBy = session.login_id.trim();

        Transaction before = findByIdOrNull(tenantId, id);
        AuditContext.captureBefore(id, AuditSnapshots.transaction(before));

        int archived = transactionContraInboxDao.archiveRejectedToDeleted(tenantId, id, rejectedBy);
        if (archived <= 0) {
            throw new BusinessException("Transaction is no longer pending approval");
        }

        int removed = maintenanceDao.deleteByIdsAndTenantId(tenantId, List.of(id));
        if (removed <= 0) {
            throw new BusinessException("Failed to remove rejected transaction");
        }
    }

    private Transaction findByIdOrNull(Integer tenantId, int id) {
        List<Transaction> rows = maintenanceDao.findByIdsAndTenantId(tenantId, List.of(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static SessionUser requireApproverSession() {
        SessionUser session = SecurityUtils.currentUser();
        AccessControlUtils.requireContraInboxApprover(session);
        if (session.login_id == null || session.login_id.isBlank()) {
            throw new BusinessException("Invalid session login id");
        }
        return session;
    }

    private static int requireId(Integer id) {
        if (id == null || id <= 0) {
            throw new BusinessException("Invalid transaction id");
        }
        return id;
    }
}
