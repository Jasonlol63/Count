package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.ProcessDescDao;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.ProcessDescService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProcessDescServiceImpl implements ProcessDescService {

    @Autowired
    private ProcessDescDao processDescDao;

    @Override
    public List<ProcessDescription> findDescriptionByTenantId(Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }
        return processDescDao.findDescriptionByTenantId(tenantId);
    }

    @Override
    @Transactional
    public void insertNewProcessDescription(ProcessDescription processDescription) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (processDescription == null) {
            throw new BusinessException("Request body is required!");
        }
        if (processDescription.getTenantId() == null) {
            throw new BusinessException("tenant_id is required!");
        }
        if (processDescription.getName() == null || processDescription.getName().isBlank()) {
            throw new BusinessException("Description name is required!");
        }

        processDescription.setName(processDescription.getName().trim().toUpperCase());

        ProcessDescription existing = processDescDao.findDescriptionByName(
                processDescription.getName(), processDescription.getTenantId());
        if (existing != null) {
            throw new BusinessException("Description name already exists!");
        }

        try {
            processDescDao.insertNewProcessDescription(processDescription);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("Description name already exists!");
        } catch (Exception e) {
            throw new BusinessException("Insert failed. Please try again!");
        }
    }

    @Override
    @Transactional
    public void deleteProcessDescriptionById(Integer id, Integer tenantId) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (id == null) {
            throw new BusinessException("id is required!");
        }
        if (tenantId == null) {
            throw new BusinessException("tenant_id is required!");
        }

        ProcessDescription processDescription = processDescDao.findDescriptionByIdAndTenantId(id, tenantId);
        if (processDescription == null) {
            throw new BusinessException("Description does not exist!");
        }

        try {
            processDescDao.deleteProcessDescriptionById(id, tenantId);
        } catch (Exception e) {
            throw new BusinessException("Delete failed. Please try again!");
        }
    }
}
