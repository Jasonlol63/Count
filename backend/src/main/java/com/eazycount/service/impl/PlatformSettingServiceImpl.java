package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.AuditSnapshots;
import com.eazycount.audit.Audited;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.PlatformSettingDao;
import com.eazycount.entity.AuditLog;
import com.eazycount.entity.PlatformSetting;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.PlatformSettingService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformSettingServiceImpl implements PlatformSettingService {

    @Autowired
    private PlatformSettingDao platformSettingDao;

    @Override
    public PlatformSetting getLink() {
        return platformSettingDao.findLink();
    }

    @Override
    @Audited(module = "PLATFORM_SETTING", action = AuditLog.Action.UPDATE, entityIdExpr = "1", sourceTable = "platform_settings")
    @Transactional
    public void updateLink(PlatformSetting platformSetting) {
        final SessionUser current = SecurityUtils.currentUser();
        AccessControlUtils.requireWritable(current);
        if (current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        AuditContext.captureBefore(1, AuditSnapshots.platformSetting(platformSettingDao.findLink()));

        String link = platformSetting.getTelegramSupportLink();
        if (link != null) {
            link = link.trim();
        }
        if (link != null && !link.isEmpty() && !link.matches("^https?://\\S+$")) {
            throw new BusinessException("Telegram link must start with http:// or https://");
        }
        platformSetting.setTelegramSupportLink((link == null || link.isEmpty()) ? null : link);

        // Singleton row: always target id = 1 regardless of what the client sent.
        platformSetting.setId(1);
        platformSetting.setUpdatedBy(current.login_id);
        if ("owner".equalsIgnoreCase(current.user_type)) {
            platformSetting.setUpdatedByType(PlatformSetting.UpdatedByType.OWNER);
        } else {
            // "user" 或 "member" 都映射为 USER
            platformSetting.setUpdatedByType(PlatformSetting.UpdatedByType.USER);
        }

        try {
            platformSettingDao.updateLink(platformSetting);
        } catch (Exception e) {
            throw new BusinessException("Update failed. Please try again!");
        }

        AuditContext.captureAfter(1, AuditSnapshots.platformSetting(platformSettingDao.findLink()));
    }
}
