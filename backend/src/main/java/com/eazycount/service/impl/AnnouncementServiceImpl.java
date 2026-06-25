package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AnnouncementDao;
import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnnouncementServiceImpl implements AnnouncementService {

    @Autowired
    private AnnouncementDao announcementDao;

    @Override
    public List<Announcements> findAllAnnouncement() {

        return announcementDao.findAllAnnouncement();
    }

    @Override
    public List<Maintenance> findAllMaintenance() {

        return announcementDao.findAllMaintenance();
    }

    @Override
    public List<Announcements> findDashboardAnnouncements(){
        return announcementDao.findDashboardAnnouncements();
    }

    @Override
    public List<Maintenance> findMaintenanceInLogin() {
        return announcementDao.findMaintenanceInLogin();
    }

    @Override
    public void addMaintenance(Maintenance maintenance) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        if (maintenance.getCreatedBy() == null) {
            maintenance.setCreatedBy(current.user_id);
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
        } catch (Exception e) {
            throw new BusinessException("Insert failed. Please try again!");
        }
    }


    @Override
    public void addAnnouncement(Announcements announcements) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        if (announcements.getCreatedBy() == null) {
            announcements.setCreatedBy(current.user_id);
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

        }catch (Exception e){
            throw new BusinessException("Insert failed. Please try again!");
        }

    }

    @Override
    public void updateAnnouncement(Announcements announcements) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        // 2. 校验要更新的公告 id（来自前端 @RequestBody，不是登录用户 id）
        if (announcements.getId() == null || announcements.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            announcements.setTitle(announcements.getTitle());
            announcements.setContent(announcements.getContent());
            announcementDao.updateAnnouncement(announcements);
        }catch (Exception e){
            throw new BusinessException("Update failed. Please try again!");
        }
    }

    @Override
    public void updateMaintenance(Maintenance maintenance) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        if (maintenance.getId() == null || maintenance.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }

        try{
            maintenance.setPrefix(maintenance.getPrefix());
            maintenance.setContent(maintenance.getContent());
            announcementDao.updateMaintenance(maintenance);
        }catch (Exception e){
            throw new BusinessException("Update failed. Please try again!");
        }
    }

    @Override
    public void deleteAnnouncement(Announcements announcements) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        if (announcements.getId() == null || announcements.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            announcementDao.deleteAnnouncement(announcements);
        }catch (Exception e){
            throw new BusinessException("Delete failed. Please try again!");
        }
    }

    @Override
    public void deleteMaintenance(Maintenance maintenance) {
        final SessionUser current = SecurityUtils.currentUser();
        if (current == null || current.user_id == null) {
            throw new BusinessException("User not logged in");
        }

        if (maintenance.getId() == null || maintenance.getId() == 0) {
            throw new BusinessException("Id not found. Please try again!");
        }
        try{
            announcementDao.deleteMaintenance(maintenance);
        }catch (Exception e){
            throw new BusinessException("Delete failed. Please try again!");
        }
    }
}
