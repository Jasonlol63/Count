package com.eazycount.service;

import com.eazycount.entity.ProcessDescription;

import java.util.List;

public interface ProcessDescService {

    //CRUD for ProcessDescription
    List<ProcessDescription> findDescriptionByTenantId(Integer tenantId);

    void insertNewProcessDescription(ProcessDescription processDescription);

    void deleteProcessDescriptionById(Integer id, Integer tenantId);
}
