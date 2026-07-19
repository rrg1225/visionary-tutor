package com.visionary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceTraceDto {

    private Long artifactId;

    private List<RevisionEventDto> revisions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RevisionEventDto {

        private int revisionRound;

        private double compositeScore;

        private double scoreDelta;

        private String decision;

        private String criticFeedback;
    }
}
