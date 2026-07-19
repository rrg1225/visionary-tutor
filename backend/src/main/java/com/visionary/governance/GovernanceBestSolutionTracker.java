package com.visionary.governance;

import com.visionary.entity.GeneratedArtifact;
import lombok.Getter;

/**
 * Tracks the highest composite-scoring revision snapshot during a bounded Critic loop.
 */
public final class GovernanceBestSolutionTracker {

    @Getter
    private double bestScore;

    private final CompositeScoreCalculator compositeScoreCalculator;

    public GovernanceBestSolutionTracker(CompositeScoreCalculator compositeScoreCalculator, double initialScore) {
        this.compositeScoreCalculator = compositeScoreCalculator;
        this.bestScore = initialScore;
    }

    public double scoreOf(GeneratedArtifact artifact, double llmScore) {
        return compositeScoreCalculator.computeCompositeScore(artifact, llmScore);
    }

    public boolean registerIfBetter(GeneratedArtifact artifact, double llmScore) {
        double candidate = scoreOf(artifact, llmScore);
        if (candidate >= bestScore) {
            bestScore = candidate;
            return true;
        }
        return false;
    }
}
