package com.eazycount.controller;

import com.eazycount.entity.Announcements;
import com.eazycount.entity.Maintenance;
import com.eazycount.service.AnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    @Autowired
    private AnnouncementService announcementService;

    @GetMapping("/listAnnouncement")
    public ResponseEntity<Map<String, Object>> showAnnouncement(){
        final List<Announcements> announcements = announcementService.findAllAnnouncement();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data",announcements)
        );
    }

    @GetMapping("/listMaintenance")
    public ResponseEntity<Map<String, Object>> showMaintenance(){
        final List<Maintenance> maintenance = announcementService.findAllMaintenance();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data", maintenance)
        );
    }

    @GetMapping("/getDashboardAnnouncements")
    public ResponseEntity<Map<String, Object>> getDashboardAnnouncements(){
        final List<Announcements> announcements = announcementService.findDashboardAnnouncements();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data",announcements)
        );
    }


    @GetMapping("/getMaintenanceInLogin")
    public ResponseEntity<Map<String, Object>> getMaintenanceInLogin(){
        final List<Maintenance> maintenance = announcementService.findMaintenanceInLogin();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data",maintenance)
        );
    }

    @PostMapping("/addAnnouncementContent")
    public ResponseEntity<Map<String, Object>> addAnnouncementContentPage(@RequestBody Announcements announcements){
        announcementService.addAnnouncement(announcements);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Announcement created successfully",
                "data", announcements
        ));
    }

    @PostMapping("/addMaintenanceContent")
    public ResponseEntity<Map<String, Object>> addMaintenanceContentPage(@RequestBody Maintenance maintenance){
        announcementService.addMaintenance(maintenance);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Maintenance created successfully",
                "data", maintenance
        ));
    }

    @PostMapping("/updateAnnouncement")
    public ResponseEntity<Map<String, Object>> updateAnnouncement(@RequestBody Announcements announcements){
        announcementService.updateAnnouncement(announcements);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Announcement updated successfully",
                "data", announcements
        ));
    }

    @PostMapping("/updateMaintenance")
    public ResponseEntity<Map<String, Object>> updateMaintenance(@RequestBody Maintenance maintenance){
        announcementService.updateMaintenance(maintenance);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Maintenance updated successfully",
                "data", maintenance
        ));
    }

    @PostMapping("/deleteAnnouncement")
    public ResponseEntity<Map<String, Object>> deleteAnnouncement(@RequestBody Announcements announcements) {
        announcementService.deleteAnnouncement(announcements);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Announcement deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/deleteMaintenance")
    public ResponseEntity<Map<String, Object>> deleteMaintenance(@RequestBody Maintenance maintenance) {
        announcementService.deleteMaintenance(maintenance);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Maintenance deleted successfully");
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

}
