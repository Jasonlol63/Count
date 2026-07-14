package com.eazycount.service;

import com.eazycount.dto.ProcessDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;

import java.util.List;

public interface ProcessService {

    List<ProcessDTO> findProcessByTenantId(Integer tenantId);

    ProcessDTO addNewProcess(ProcessDTO processDTO);

    ProcessDTO updateProcess(ProcessDTO processDTO);

    void deleteProcessById(Integer id, Integer tenantId);

    //Update Status of Process
    Process updateProcessStatus(Integer id, Integer tenantId);

    //CRUD for ProcessDescription
    List<ProcessDescription> findDescriptionByTenantId(Integer tenantId);

    void insertNewProcessDescription(ProcessDescription processDescription);

    void deleteProcessDescriptionById(Integer id, Integer tenantId);

}
