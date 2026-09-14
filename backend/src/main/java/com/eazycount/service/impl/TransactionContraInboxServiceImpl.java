package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TransactionContraInboxDao;
import com.eazycount.dao.MaintenanceDao;
import com.eazycount.dto.TransactionContraInboxDTO;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionContraInboxService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionContraInboxServiceImpl implements TransactionContraInboxService {

    @Autowired
    private TransactionContraInboxDao transactionContraInboxDao;

    @Autowired
    private MaintenanceDao maintenanceDao;

    @Override
    public List<TransactionContraInboxDTO> listPending(Integer tenantId) {
        requireApproverSession();
        return transactionContraInboxDao.findPendingRows(requireTenantId(tenantId));
    }

    @Override
    @Transactional
    public void approve(TransactionContraInboxDTO request) {
        SessionUser session = requireApproverSession();
        int tenantId = requireTenantId(request != null ? request.getTenantId() : null);
        int id = requireId(request != null ? request.getId() : null);

        int updated = transactionContraInboxDao.approvePendingTransaction(tenantId, id, session.login_id.trim());
        if (updated <= 0) {
            throw new BusinessException("Transaction is no longer pending approval");
        }
    }

    @Override
    @Transactional
    public void reject(TransactionContraInboxDTO request) {
        SessionUser session = requireApproverSession();
        int tenantId = requireTenantId(request != null ? request.getTenantId() : null);
        int id = requireId(request != null ? request.getId() : null);
        String rejectedBy = session.login_id.trim();

        int archived = transactionContraInboxDao.archiveRejectedToDeleted(tenantId, id, rejectedBy);
        if (archived <= 0) {
            throw new BusinessException("Transaction is no longer pending approval");
        }

        int removed = maintenanceDao.deleteByIdsAndTenantId(tenantId, List.of(id));
        if (removed <= 0) {
            throw new BusinessException("Failed to remove rejected transaction");
        }
    }

    private static SessionUser requireApproverSession() {
        SessionUser session = SecurityUtils.currentUser();
        AccessControlUtils.requireContraInboxApprover(session);
        if (session.login_id == null || session.login_id.isBlank()) {
            throw new BusinessException("Invalid session login id");
        }
        return session;
    }

    private static int requireTenantId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid tenant id");
        }
        return tenantId;
    }

    private static int requireId(Integer id) {
        if (id == null || id <= 0) {
            throw new BusinessException("Invalid transaction id");
        }
        return id;
    }
}
