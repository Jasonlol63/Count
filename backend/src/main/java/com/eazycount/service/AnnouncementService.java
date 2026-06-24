package com.eazycount.service;

import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;
import org.springframework.stereotype.Service;

import java.util.List;

public interface AnnouncementService {
    List<Announcements> findAllAnnouncement();

    List<Maintenance> findAllMaintenance();

    void addAnnouncement(Announcements announcements);

    void updateAnnouncement(Announcements announcements);

    void deleteAnnouncement(Announcements announcements);

    void addMaintenance(Maintenance maintenance);

    void updateMaintenance(Maintenance maintenance);

    void deleteMaintenance(Maintenance maintenance);
}
