package com.eazycount.dao;

import com.eazycount.dto.ProcessDescriptionDTO;
import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProcessDao {

    List<ProcessDescriptionDTO> findAllProcessByTenantId(@Param("tenantId") Integer tenantId);

    Process findProcessByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    void insertProcessDetails(Process process);

    void updateStatusById(@Param("id") Integer id, @Param("status") String status);

    /* Description CRUD operations */
    ProcessDescription findDescriptionById(@Param("id") Integer id);

    List<ProcessDescription> findDescriptionsByTenantId(@Param("tenantId") Integer tenantId);

    ProcessDescription findDescriptionByNameAndTenantId(@Param("tenantId") Integer tenantId, @Param("name") String name);

    List<ProcessDescription> findAllDescription();

    void insertNewDescription(ProcessDescription processDescription);

    void deleteDescriptionById(@Param("id") Integer id);

}
