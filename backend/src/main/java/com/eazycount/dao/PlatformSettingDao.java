package com.eazycount.dao;

import com.eazycount.entity.PlatformSetting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSettingDao {

    PlatformSetting findLink();

    void updateLink(PlatformSetting platformSetting);
}
