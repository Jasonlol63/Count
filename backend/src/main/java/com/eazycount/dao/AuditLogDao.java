package com.eazycount.dao;

import com.eazycount.dto.AuditLogDTO;
import com.eazycount.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Backs the IT audit-log console — record/query. See docs/it-role-audit-log.md. */
@Mapper
public interface AuditLogDao {

    /** Populates {@code auditLog.id} via useGeneratedKeys. */
    int insert(AuditLog auditLog);

    List<AuditLog> search(@Param("q") AuditLogDTO query);

    long countSearch(@Param("q") AuditLogDTO query);

    AuditLogDTO summary(@Param("q") AuditLogDTO query);
}
