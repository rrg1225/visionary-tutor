package com.visionary.recommendation;

import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRecommendationEngineTest {

    @Test
    void freshnessBonusRanksNewestHighest() {
        GeneratedArtifact older = artifact(1L, LocalDateTime.of(2026, 1, 1, 10, 0));
        GeneratedArtifact newer = artifact(2L, LocalDateTime.of(2026, 1, 3, 10, 0));

        double olderBonus = HybridRecommendationEngine.freshnessBonus(older, List.of(older, newer));
        double newerBonus = HybridRecommendationEngine.freshnessBonus(newer, List.of(older, newer));

        assertTrue(newerBonus > olderBonus);
        assertEquals(HybridRecommendationEngine.MAX_FRESHNESS_BOOST, newerBonus, 0.0001D);
    }

    private static GeneratedArtifact artifact(Long id, LocalDateTime created) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId(id);
        artifact.setGmtCreated(created);
        artifact.setTitle("demo");
        artifact.setArtifactType(GeneratedArtifact.ArtifactType.HANDOUT);
        return artifact;
    }
}
