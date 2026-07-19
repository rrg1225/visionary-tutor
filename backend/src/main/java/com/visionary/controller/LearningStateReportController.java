package com.visionary.controller;

import com.visionary.security.AuthContext;
import com.visionary.service.LearningStateReportService;
import com.visionary.service.LearningStateReportService.CreateReportRequest;
import com.visionary.service.LearningStateReportService.ReportView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/learning-state-reports")
@RequiredArgsConstructor
public class LearningStateReportController {

    private final LearningStateReportService reportService;

    @PostMapping
    public ReportView create(@RequestBody CreateReportRequest request) {
        return reportService.create(requireUserId(), request);
    }

    @GetMapping
    public List<ReportView> list(
            @RequestParam(value = "contextType", required = false) String contextType,
            @RequestParam(value = "contextKey", required = false) String contextKey
    ) {
        Long userId = requireUserId();
        if (contextType != null || contextKey != null) {
            return reportService.listByContext(userId, contextType, contextKey);
        }
        return reportService.listMine(userId);
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录后才能保存学习状态报告"));
    }
}
