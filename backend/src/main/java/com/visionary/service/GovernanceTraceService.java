package com.visionary.service;

import com.visionary.dto.GovernanceTraceDto;
import com.visionary.entity.AgentRevisionLog;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.governance.CompositeScoreCalculator;
import com.visionary.governance.GovernanceCircuitBreaker;
import com.visionary.governance.GovernanceCircuitBreaker.BreakerDecision;
import com.visionary.mapper.GovernanceTraceMapper;
import com.visionary.repository.AgentRevisionLogRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceTraceService {

    private final AgentRevisionLogRepository revisionLogRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final GovernanceCircuitBreaker governanceCircuitBreaker;
    private final CompositeScoreCalculator compositeScoreCalculator;
    private final GovernanceTraceMapper governanceTraceMapper;

    @Transactional
    public BreakerDecision recordAndEvaluate(
            String artifactId,
            int currentRound,
            double llmScore,
            double previousScore,
            String feedback) {
        Long artifactPk = requireArtifactId(artifactId);

        GeneratedArtifact artifact = artifactRepository.findById(artifactPk).orElse(null);
        if (artifact == null) {
            log.warn("[GovernanceTrace] Artifact {} not found, objective rule score defaults to 0", artifactPk);
        }

        double compositeScore = compositeScoreCalculator.computeCompositeScore(artifact, llmScore);
        double scoreDelta = compositeScoreCalculator.computeScoreDelta(compositeScore, previousScore);
        Double priorRoundDelta = resolvePriorRoundDelta(artifactPk, currentRound);
        BreakerDecision decision = governanceCircuitBreaker.evaluate(
                currentRound,
                compositeScore,
                previousScore,
                priorRoundDelta
        );

        AgentRevisionLog revisionLog = AgentRevisionLog.builder()
                .artifactId(artifactPk)
                .revisionRound(currentRound)
                .compositeScore(compositeScore)
                .scoreDelta(scoreDelta)
                .breakerDecision(toEntityDecision(decision))
                .criticFeedback(feedback)
                .build();
        revisionLogRepository.save(revisionLog);

        log.info(
                "[GovernanceTrace] artifactId={}, round={}, composite={}, delta={}, decision={}",
                artifactPk, currentRound, compositeScore, scoreDelta, decision.decisionCode()
        );
        return decision;
    }

    @Transactional(readOnly = true)
    public GovernanceTraceDto getTrace(String artifactId) {
        Long artifactPk = parseArtifactId(artifactId);
        if (artifactPk == null) {
            log.debug("[GovernanceTrace] Invalid artifactId '{}', returning empty trace", artifactId);
            return governanceTraceMapper.toDto(null, List.of());
        }

        List<AgentRevisionLog> logs = revisionLogRepository.findByArtifactIdOrderByRevisionRoundAsc(artifactPk);
        return governanceTraceMapper.toDto(artifactPk, logs);
    }

    private Long requireArtifactId(String artifactId) {
        Long parsed = parseArtifactId(artifactId);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid artifactId: " + artifactId);
        }
        return parsed;
    }

    private Long parseArtifactId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(artifactId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double resolvePriorRoundDelta(Long artifactPk, int currentRound) {
        if (currentRound < 2) {
            return null;
        }
        return revisionLogRepository.findByArtifactIdAndRevisionRound(artifactPk, currentRound - 1)
                .map(AgentRevisionLog::getScoreDelta)
                .orElse(null);
    }

    private AgentRevisionLog.BreakerDecision toEntityDecision(BreakerDecision decision) {
        return switch (decision.decisionCode()) {
            case GovernanceCircuitBreaker.CODE_CONTINUE -> AgentRevisionLog.BreakerDecision.CONTINUE;
            case GovernanceCircuitBreaker.CODE_HALT_MAX_ROUND -> AgentRevisionLog.BreakerDecision.BREAK_MAX_ROUNDS;
            case GovernanceCircuitBreaker.CODE_HALT_NEGATIVE_DELTA,
                 GovernanceCircuitBreaker.CODE_HALT_CONVERGENCE,
                 GovernanceCircuitBreaker.CODE_HALT_LOW_MARGINAL_UTILITY ->
                    AgentRevisionLog.BreakerDecision.BREAK_NO_GAIN;
            default -> AgentRevisionLog.BreakerDecision.BREAK_MANUAL;
        };
    }
}
