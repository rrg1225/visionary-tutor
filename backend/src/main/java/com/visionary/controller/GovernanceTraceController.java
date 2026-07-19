package com.visionary.controller;

import com.visionary.dto.GovernanceTraceDto;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.security.AuthContext;
import com.visionary.service.GovernanceTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class GovernanceTraceController {

    private final GovernanceTraceService governanceTraceService;
    private final GeneratedArtifactRepository artifactRepository;
    private final LearningSessionRepository learningSessionRepository;

    @GetMapping("/{artifactId}/governance-trace")
    public GovernanceTraceDto getGovernanceTrace(@PathVariable Long artifactId) {
        Long userId = AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问治理记录"));
        GeneratedArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "resource not found"));
        if (!learningSessionRepository.existsByIdAndUserId(artifact.getLearningSessionId(), userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "resource not found");
        }
        return governanceTraceService.getTrace(artifactId.toString());
    }
}
