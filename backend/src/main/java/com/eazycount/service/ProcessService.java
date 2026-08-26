package com.eazycount.service;

import com.eazycount.dto.ProcessDTO;
import com.eazycount.entity.Process;

import java.util.List;

public interface ProcessService {

    List<ProcessDTO> findProcessByTenantId(Integer tenantId);

    ProcessDTO addNewProcess(ProcessDTO processDTO);

    ProcessDTO updateProcess(ProcessDTO processDTO);

    void deleteProcessById(Integer id, Integer tenantId);

    //Update Status of Process
    Process updateProcessStatus(Integer id, Integer tenantId);

}
