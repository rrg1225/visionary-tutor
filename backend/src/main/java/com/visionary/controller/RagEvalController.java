package com.visionary.controller;

import com.visionary.rag.eval.RagEvalReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rag-eval")
@RequiredArgsConstructor
public class RagEvalController {

    private final RagEvalReportService reportService;

    @GetMapping("/latest")
    @PreAuthorize("hasRole('ADMIN')")
    public RagEvalReportService.LatestRagEvalReport latest() {
        return reportService.latest();
    }
}
