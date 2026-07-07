package com.eazycount.service;

import com.eazycount.dto.ProcessDescriptionDTO;
import com.eazycount.entity.ProcessDescription;

import java.util.List;

public interface ProcessService {

    List<ProcessDescriptionDTO> findAllProcessByTenantId(Integer tenantId);

    void insertProcessDetails(ProcessDescriptionDTO processDescDTO);

    String updateStatusById(Integer id);

    /* Description CRUD Operations use */

    List<ProcessDescription> findAllDescription(Integer tenantId);

    void insertNewDescription(ProcessDescription processDescription);

    void deleteDescriptionById(Integer id, Integer tenantId);
}
