package com.eazycount.dao;

import com.eazycount.entity.BankProcess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface BankProcessResendDao {

    void updateResendSchedule(@Param("id") Integer id,
                              @Param("tenantId") Integer tenantId,
                              @Param("resendScheduleDayStart") LocalDate resendScheduleDayStart,
                              @Param("resendScheduleDayEnd") LocalDate resendScheduleDayEnd,
                              @Param("resendScheduleFrequency") BankProcess.Frequency resendScheduleFrequency);

    void clearResendSchedule(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int deleteSkippedResendConsolidated(@Param("tenantId") Integer tenantId,
                                        @Param("bankProcessId") Integer bankProcessId,
                                        @Param("postedDate") LocalDate postedDate);
}
