package com.visionary.controller;

import com.visionary.security.AuthContext;
import com.visionary.service.UserDataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final UserDataExportService exportService;

    @GetMapping("/export")
    public UserDataExportService.UserDataExport export() {
        return exportService.exportUserData(requireUserId());
    }

    @DeleteMapping("/memory")
    public UserDataExportService.MemoryDeleteResult deleteLearningMemory() {
        return exportService.deleteLearningMemories(requireUserId());
    }

    @DeleteMapping("/account")
    public UserDataExportService.AccountDeleteResult deleteAccount() {
        return exportService.deleteAccount(requireUserId());
    }

    @GetMapping("/camera-policy")
    public Map<String, Object> cameraPolicy() {
        return exportService.privacyPolicy();
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Registered login required"));
    }
}
