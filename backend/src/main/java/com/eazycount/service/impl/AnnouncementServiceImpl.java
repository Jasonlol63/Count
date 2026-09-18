package com.eazycount.service.impl;

import com.eazycount.audit.AuditContext;
import com.eazycount.audit.AuditSnapshots;
import com.eazycount.audit.Audited;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.AnnouncementDao;
import com.eazycount.entity.AuditLog;
import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AnnouncementService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnnouncementServiceImpl implements AnnouncementService {

    @Autowired
    private AnnouncementDao announcementDao;

    @Override
    public List<Announcements> findAllAnnouncement() {return announcementDao.findAllAnnouncement();}

    @Override
    public List<Maintenance> findAllMaintenance() {return announcementDao.findAllMaintenance();}

    @Override
    public List<Announcements> findDashboardAnnouncements(){
        return announcementDao.findDashboardAnnouncements();
    }

    @Override
    public List<Maintenance> findMaintenanceInLogin() {
        return announcementDao.findMaintenanceInLogin();
    }

    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.CREATE, entityIdExpr = "#maintenance.id", sourceTable = "maintenance_marquee")
    public void addMaintenance(Maintenance maintenance) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        if (maintenance.getCreatedBy() == null || maintenance.getCreatedBy().isBlank()) {
            maintenance.setCreatedBy(current.login_id);
        }

        if (maintenance.getUserType() == null) {
            if ("owner".equalsIgnoreCase(current.user_type)) {
                maintenance.setUserType(Maintenance.User.OWNER);
            } else {
                // "user" 或 "member" 都映射为 USER
                maintenance.setUserType(Maintenance.User.USER);
            }
        }
        try {
            // companyCode 默认 C168
            if (maintenance.getCompanyCode() == null || maintenance.getCompanyCode().isBlank()) {
                maintenance.setCompanyCode("C168");
            }

            if (maintenance.getStatus() == null) {
                maintenance.setStatus(Maintenance.Status.ACTIVE);
            }

            maintenance.setPrefix(maintenance.getPrefix());
            maintenance.setContent(maintenance.getContent());
            maintenance.setCreatedAt(maintenance.getCreatedAt());
            announcementDao.addMaintenance(maintenance);
            AuditContext.captureAfter(maintenance.getId(), AuditSnapshots.maintenance(maintenance));
        } catch (Exception e) {
            throw new BusinessException("Insert failed. Please try again!");
        }
    }


    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.CREATE, entityIdExpr = "#announcements.id", sourceTable = "announcements")
    public void addAnnouncement(Announcements announcements) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        if (announcements.getCreatedBy() == null || announcements.getCreatedBy().isBlank()) {
            announcements.setCreatedBy(current.login_id);
        }

        if (announcements.getUserType() == null) {
            if ("owner".equalsIgnoreCase(current.user_type)) {
                announcements.setUserType(Announcements.User.OWNER);
            } else {
                // "user" 或 "member" 都映射为 USER
                announcements.setUserType(Announcements.User.USER);
            }
        }
        try{
            // companyCode 默认 C168
            if (announcements.getCompanyCode() == null || announcements.getCompanyCode().isBlank()) {
                announcements.setCompanyCode("C168");
            }

            if (announcements.getStatus() == null) {
                announcements.setStatus(Announcements.Status.ACTIVE);
            }

            announcements.setTitle(announcements.getTitle());
            announcements.setContent(announcements.getContent());
            announcements.setCreatedAt(announcements.getCreatedAt());
            announcementDao.addAnnouncement(announcements);
            AuditContext.captureAfter(announcements.getId(), AuditSnapshots.announcement(announcements));

        }catch (Exception e){
            throw new BusinessException("Insert failed. Please try again!");
        }

    }

    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.UPDATE, entityIdExpr = "#announcements.id", sourceTable = "announcements")
    public void updateAnnouncement(Announcements announcements) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        // 2. 校验要更新的公告 id（来自前端 @RequestBody，不是登录用户 id）
        if (announcements.getId() == null || announcements.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            AuditContext.captureBefore(announcements.getId(), AuditSnapshots.announcement(announcementDao.findAnnouncementById(announcements.getId())));
            announcements.setTitle(announcements.getTitle());
            announcements.setContent(announcements.getContent());
            announcementDao.updateAnnouncement(announcements);
            AuditContext.captureAfter(announcements.getId(), AuditSnapshots.announcement(announcementDao.findAnnouncementById(announcements.getId())));
        }catch (Exception e){
            throw new BusinessException("Update failed. Please try again!");
        }
    }

    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.UPDATE, entityIdExpr = "#maintenance.id", sourceTable = "maintenance_marquee")
    public void updateMaintenance(Maintenance maintenance) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        if (maintenance.getId() == null || maintenance.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }

        try{
            AuditContext.captureBefore(maintenance.getId(), AuditSnapshots.maintenance(announcementDao.findMaintenanceById(maintenance.getId())));
            maintenance.setPrefix(maintenance.getPrefix());
            maintenance.setContent(maintenance.getContent());
            announcementDao.updateMaintenance(maintenance);
            AuditContext.captureAfter(maintenance.getId(), AuditSnapshots.maintenance(announcementDao.findMaintenanceById(maintenance.getId())));
        }catch (Exception e){
            throw new BusinessException("Update failed. Please try again!");
        }
    }

    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.DELETE, entityIdExpr = "#announcements.id", sourceTable = "announcements")
    public void deleteAnnouncement(Announcements announcements) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        if (announcements.getId() == null || announcements.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            AuditContext.captureBefore(announcements.getId(), AuditSnapshots.announcement(announcementDao.findAnnouncementById(announcements.getId())));
            announcementDao.deleteAnnouncement(announcements);
        }catch (Exception e){
            throw new BusinessException("Delete failed. Please try again!");
        }
    }

    @Override
    @Transactional
    @Audited(module = "ANNOUNCEMENT", action = AuditLog.Action.DELETE, entityIdExpr = "#maintenance.id", sourceTable = "maintenance_marquee")
    public void deleteMaintenance(Maintenance maintenance) {
        final SessionUser current = AccessControlUtils.requireLoggedIn();
        AccessControlUtils.requireWritable(current);

        if (maintenance.getId() == null || maintenance.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            AuditContext.captureBefore(maintenance.getId(), AuditSnapshots.maintenance(announcementDao.findMaintenanceById(maintenance.getId())));
            announcementDao.deleteMaintenance(maintenance);
        }catch (Exception e){
            throw new BusinessException("Delete failed. Please try again!");
        }
    }
}
