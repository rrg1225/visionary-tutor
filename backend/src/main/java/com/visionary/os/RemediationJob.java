package com.visionary.os;

import java.util.List;

public record RemediationJob(
        String runId,
        Long learningSessionId,
        Long userId,
        String profileSnapshot,
        double accuracy,
        List<String> weakPoints,
        List<String> errorPatterns,
        String feedback,
        String source
) {}
