package com.visionary.controller;

import com.visionary.dto.DiagnosticReportRequest;
import com.visionary.entity.DiagnosticReport;
import com.visionary.service.DiagnosticReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/diagnostic-reports")
@RequiredArgsConstructor
public class DiagnosticReportController {

    private final DiagnosticReportService diagnosticReportService;

    @GetMapping
    public List<DiagnosticReport> listReports(@RequestParam Long learningSessionId) {
        return diagnosticReportService.listReportsBySession(learningSessionId);
    }

    @GetMapping("/{id}")
    public DiagnosticReport getReport(@PathVariable Long id) {
        return diagnosticReportService.getReport(id);
    }

    @PostMapping
    public ResponseEntity<DiagnosticReport> createReport(@RequestBody DiagnosticReportRequest request) {
        DiagnosticReport created = diagnosticReportService.createReport(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public DiagnosticReport updateReport(@PathVariable Long id, @RequestBody DiagnosticReportRequest request) {
        return diagnosticReportService.updateReport(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReport(@PathVariable Long id) {
        diagnosticReportService.deleteReport(id);
        return ResponseEntity.noContent().build();
    }
}
