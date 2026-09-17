package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.Audited;
import com.eazycount.dao.SystemMaintenanceModeDao;
import com.eazycount.entity.AuditLog;
import com.eazycount.security.SecurityUtils;
import com.eazycount.service.SystemMaintenanceModeService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SystemMaintenanceModeServiceImpl implements SystemMaintenanceModeService {

    private static final String REDIS_KEY = "ec:maintenance:enabled";
    private static final String ENTITY_ID = "system_maintenance_mode";

    @Autowired
    private SystemMaintenanceModeDao systemMaintenanceModeDao;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean isEnabled() {
        String cached = redisTemplate.opsForValue().get(REDIS_KEY);
        if (cached != null) {
            return "1".equals(cached);
        }
        boolean enabled = Boolean.TRUE.equals(systemMaintenanceModeDao.findEnabled());
        redisTemplate.opsForValue().set(REDIS_KEY, enabled ? "1" : "0");
        return enabled;
    }

    @Override
    @Audited(module = "SYSTEM_MAINTENANCE", action = AuditLog.Action.UPDATE, entityIdExpr = "'" + ENTITY_ID + "'", sourceTable = "system_maintenance_mode")
    public void setEnabled(boolean enabled) {
        AccessControlUtils.requireItOperator(SecurityUtils.currentUser());

        AuditContext.captureBefore(ENTITY_ID, Map.of("enabled", isEnabled()));
        systemMaintenanceModeDao.updateEnabled(enabled);
        redisTemplate.opsForValue().set(REDIS_KEY, enabled ? "1" : "0");
    }
}
