package com.eazycount.dao;

import com.eazycount.entity.ProcessDescription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProcessDescDao {

    //CRUD for ProcessDescription
    List<ProcessDescription> findDescriptionByTenantId(@Param("tenantId") Integer tenantId);

    ProcessDescription findDescriptionByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    ProcessDescription findDescriptionByName(@Param("name") String name, @Param("tenantId") Integer tenantId);

    void insertNewProcessDescription(ProcessDescription processDescription);

    void deleteProcessDescriptionById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
}
