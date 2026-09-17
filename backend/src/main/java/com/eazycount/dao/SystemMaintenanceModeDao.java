package com.eazycount.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Backs the IT console's global "kick everyone" maintenance switch — a singleton row (id=1). */
@Mapper
public interface SystemMaintenanceModeDao {

    Boolean findEnabled();

    void updateEnabled(@Param("enabled") boolean enabled);
}
