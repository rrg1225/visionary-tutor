package com.visionary.dto;

import java.util.List;

public record DiagnosticReportRequest(
        Long learningSessionId,
        String diagnosisId,
        String reasoningTrace,
        String ragApplicationContext,
        String ragAlgorithmContext,
        String ragMathContext,
        List<DiagnosticWeakNodeRequest> weakNodes
) {
}
