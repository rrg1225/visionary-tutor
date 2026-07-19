package com.visionary.mapper;

import com.visionary.dto.GovernanceTraceDto;
import com.visionary.dto.GovernanceTraceDto.RevisionEventDto;
import com.visionary.entity.AgentRevisionLog;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GovernanceTraceMapper {

    public GovernanceTraceDto toDto(Long artifactId, List<AgentRevisionLog> logs) {
        List<RevisionEventDto> revisions = logs == null
                ? List.of()
                : logs.stream().map(this::toEventDto).toList();
        return GovernanceTraceDto.builder()
                .artifactId(artifactId)
                .revisions(revisions)
                .build();
    }

    public RevisionEventDto toEventDto(AgentRevisionLog log) {
        return RevisionEventDto.builder()
                .revisionRound(log.getRevisionRound() != null ? log.getRevisionRound() : 0)
                .compositeScore(log.getCompositeScore() != null ? log.getCompositeScore() : 0.0D)
                .scoreDelta(log.getScoreDelta() != null ? log.getScoreDelta() : 0.0D)
                .decision(log.getBreakerDecision() != null ? log.getBreakerDecision().name() : "")
                .criticFeedback(log.getCriticFeedback())
                .build();
    }
}
