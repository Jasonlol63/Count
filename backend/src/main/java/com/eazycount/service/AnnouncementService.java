package com.eazycount.service;

import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;

import java.util.List;

public interface AnnouncementService {
    List<Announcements> findAllAnnouncement();

    List<Maintenance> findAllMaintenance();

    List<Announcements> findDashboardAnnouncements();

    List<Maintenance> findMaintenanceInLogin();

    void addAnnouncement(Announcements announcements);

    void updateAnnouncement(Announcements announcements);

    void deleteAnnouncement(Announcements announcements);

    void addMaintenance(Maintenance maintenance);

    void updateMaintenance(Maintenance maintenance);

    void deleteMaintenance(Maintenance maintenance);
}
