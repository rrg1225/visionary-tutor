package com.visionary.service;

import com.visionary.dto.ResourceRecommendationDto;
import com.visionary.dto.ResourceRecommendationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.rag.LexicalSimilarityScorer;
import com.visionary.recommendation.HybridRecommendationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceRecommendationServiceTest {

    private static final LocalDateTime FIXED_GMT_CREATED = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

    @Mock
    private ResourceVectorIndexService indexService;

    private ResourceRecommendationService service;

    @BeforeEach
    void setUp() {
        HybridRecommendationEngine engine = new HybridRecommendationEngine(
                null,
                new LexicalSimilarityScorer(),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        service = new ResourceRecommendationService(engine, indexService);
    }

    @Test
    void returnsEmptyWhenNoArtifacts() {
        ResourceRecommendationResponse empty = service.recommend(
                List.of(), "大二计算机", "CNN 薄弱", "visual", false);
        assertTrue(empty.recommendations().isEmpty());
        assertTrue(empty.allArtifactIds().isEmpty());

        ResourceRecommendationResponse nullArtifacts = service.recommend(
                null, "大二计算机", "CNN 薄弱", "visual", false);
        assertTrue(nullArtifacts.recommendations().isEmpty());
        assertTrue(nullArtifacts.allArtifactIds().isEmpty());

        verifyNoInteractions(indexService);
    }

    @Test
    void addsSemanticScoreFromVectorRetrieval() {
        GeneratedArtifact artifact = sampleArtifact(1L, GeneratedArtifact.ArtifactType.HANDOUT, "CNN Padding 讲义");
        when(indexService.scoreSimilarArtifacts(anyString(), anyList(), eq(5)))
                .thenReturn(Map.of(1L, 0.8D));

        ResourceRecommendationResponse response = service.recommend(
                rankedCandidates(artifact),
                "偏好图解理解卷积",
                "padding stride 尺寸推导",
                "visual",
                false
        );

        ResourceRecommendationDto top = response.recommendations().get(0);
        assertEquals(80, top.score());
        assertTrue(top.recommendationReason().contains("语义高度相关"));
    }

    @Test
    void usesKeywordRuleFallbackBaseScoreWhenVectorMisses() {
        GeneratedArtifact artifact = sampleArtifact(2L, GeneratedArtifact.ArtifactType.QUIZ, "卷积基础测验");
        artifact.setGmtCreated(null);
        when(indexService.scoreSimilarArtifacts(anyString(), anyList(), eq(5)))
                .thenReturn(Map.of());

        ResourceRecommendationResponse response = service.recommend(
                List.of(artifact),
                "目标检测 IoU",
                "NMS 非极大值抑制",
                "text",
                false
        );

        ResourceRecommendationDto top = response.recommendations().get(0);
        assertEquals(40, top.score());
        assertEquals("规则兜底推荐", top.recommendationReason());
    }

    @Test
    void usesLexicalFallbackWhenVectorAndEmbeddingUnavailable() {
        GeneratedArtifact artifact = sampleArtifact(3L, GeneratedArtifact.ArtifactType.HANDOUT, "CNN Padding 讲义");
        artifact.setContentMarkdown("围绕 padding stride 卷积尺寸推导的图解讲义");
        when(indexService.scoreSimilarArtifacts(anyString(), anyList(), eq(5)))
                .thenReturn(Map.of());

        ResourceRecommendationResponse response = service.recommend(
                List.of(artifact),
                "偏好图解",
                "padding stride 尺寸推导",
                "visual",
                false
        );

        ResourceRecommendationDto top = response.recommendations().get(0);
        assertTrue(top.score() >= 40);
        assertTrue(top.recommendationReason().contains("薄弱知识点"));
    }

    @Test
    void boostsScoreWhenWeakPointMatches() {
        GeneratedArtifact artifact = sampleArtifact(4L, GeneratedArtifact.ArtifactType.HANDOUT, "NMS 非极大值抑制专题");
        artifact.setContentMarkdown("详解 NMS 非极大值抑制在目标检测中的应用");
        when(indexService.scoreSimilarArtifacts(anyString(), anyList(), eq(5)))
                .thenReturn(Map.of(4L, 0.5D));

        ResourceRecommendationResponse response = service.recommend(
                rankedCandidates(artifact),
                "目标检测",
                "NMS 非极大值抑制",
                "text",
                false
        );

        ResourceRecommendationDto top = response.recommendations().get(0);
        assertEquals(60, top.score());
        assertTrue(top.recommendationReason().contains("针对您的弱点精准推送"));
    }

    private static List<GeneratedArtifact> rankedCandidates(GeneratedArtifact primary) {
        GeneratedArtifact anchor = sampleArtifact(
                9000L + primary.getId(),
                GeneratedArtifact.ArtifactType.EXTENDED_READING,
                "freshness-anchor"
        );
        anchor.setGmtCreated(FIXED_GMT_CREATED.plusDays(1));
        anchor.setContentMarkdown("unrelated anchor content");
        return List.of(primary, anchor);
    }

    private static GeneratedArtifact sampleArtifact(
            Long id,
            GeneratedArtifact.ArtifactType type,
            String title
    ) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId(id);
        artifact.setLearningSessionId(1L);
        artifact.setRunId("run-unit-test");
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setContentMarkdown("围绕 " + title + " 的辅导内容");
        artifact.setValidationStatus("UNVERIFIED");
        artifact.setGmtCreated(FIXED_GMT_CREATED);
        return artifact;
    }
}
