package com.eazycount.controller;

import com.eazycount.entity.PlatformSetting;
import com.eazycount.service.PlatformSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class PlatformSettingController {

    @Autowired
    private PlatformSettingService platformSettingService;

    @GetMapping("/getTelegramLink")
    public ResponseEntity<Map<String, Object>> getTelegramLink() {
        final PlatformSetting setting = platformSettingService.getLink();
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("telegramSupportLink", setting == null || setting.getTelegramSupportLink() == null ? "" : setting.getTelegramSupportLink());
        data.put("updatedBy", setting == null || setting.getUpdatedBy() == null ? "" : setting.getUpdatedBy());
        data.put("updatedAt", setting == null ? null : setting.getUpdatedAt());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OK",
                "data", data
        ));
    }

    @PostMapping("/updateTelegramLink")
    public ResponseEntity<Map<String, Object>> updateTelegramLink(@RequestBody PlatformSetting platformSetting) {
        platformSettingService.updateLink(platformSetting);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Telegram link updated successfully",
                "data", platformSetting
        ));
    }
}
