package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
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

import java.util.LinkedHashMap;
import java.util.Map;

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

        AuditContext.captureBefore(1, platformSettingSnapshot(platformSettingDao.findLink()));

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

        AuditContext.captureAfter(1, platformSettingSnapshot(platformSettingDao.findLink()));
    }

    /** {@code platform_settings} column names, not {@link PlatformSetting}'s Java field names. */
    private static Map<String, Object> platformSettingSnapshot(PlatformSetting s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", s.getId());
        snapshot.put("telegram_support_link", s.getTelegramSupportLink());
        snapshot.put("updated_by", s.getUpdatedBy());
        snapshot.put("updated_by_type", s.getUpdatedByType());
        snapshot.put("updated_at", s.getUpdatedAt());
        return snapshot;
    }
}
