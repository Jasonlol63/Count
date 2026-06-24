package com.eazycount.dao;


import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;

import java.util.List;

public interface AnnouncementDao {
    List<Announcements> findAllAnnouncement();

    List<Maintenance> findAllMaintenance();

    void addAnnouncement(Announcements announcements);

    void updateAnnouncement(Announcements announcements);

    void deleteAnnouncement(Announcements announcements);

    void addMaintenance(Maintenance maintenance);

    void updateMaintenance(Maintenance maintenance);

    void deleteMaintenance(Maintenance maintenance);

}
