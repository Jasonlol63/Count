package com.eazycount.dao;

import com.eazycount.dto.ProcessDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDay;
import com.eazycount.entity.ProcessDescription;
import com.eazycount.entity.ProcessDescriptionLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProcessDao {

    List<ProcessDTO> findProcessByTenantId(@Param("tenantId") Integer tenantId);

    Process findProcessCodeByTenantId(@Param("tenantId") Integer tenantId, @Param("code") String code);

    Process findProcessById(@Param("id") Integer id);

    Process findProcessByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    //Insert Process, ProcessDescriptionLink, ProcessDay
    void insertNewProcess(Process process);

    void insertProcessDescriptionLinkBatch(@Param("list") List<ProcessDescriptionLink> list);

    void insertProcessDayBatch(@Param("list") List<ProcessDay> list);

    // Update process children: delete-all then re-insert (same batch inserts as add)
    void deleteProcessDescriptionLinkByProcessId(@Param("processId") Integer processId);

    void deleteProcessDayByProcessId(@Param("processId") Integer processId);

    void updateProcessDetails(Process process);

    // Delete Process Use
    void deleteProcessById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    //Update Status of Process
    void updateProcessStatus(@Param("id") Integer id, @Param("tenantId") Integer tenantId, @Param("status") Process.Status status);

    //CRUD for ProcessDescription
    List<ProcessDescription> findDescriptionByTenantId(@Param("tenantId") Integer tenantId);

    ProcessDescription findDescriptionByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    ProcessDescription findDescriptionByName(@Param("name") String name, @Param("tenantId") Integer tenantId);

    void insertNewProcessDescription(ProcessDescription processDescription);

    void deleteProcessDescriptionById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

}
