package com.visionary.controller;

import com.visionary.client.ProviderCircuitBreaker;
import com.visionary.config.AiApiConfig;
import com.visionary.rag.RagRetrievalService;
import com.visionary.rag.VectorDbService;
import com.visionary.service.LocalMockService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HealthControllerTest {

    @Test
    void publicHealthDoesNotExposeInfrastructureDetails() {
        HealthController controller = controller();

        Map<String, Object> body = controller.health().getBody();

        // ragHaAvailable 是 ai_engine/rag_eval.py 预检依赖的公开契约（粗粒度布尔），
        // 详细依赖状态仍只在 /api/ops/health 暴露。
        assertThat(body).containsOnlyKeys("status", "service", "ragHaAvailable", "timestamp");
        assertThat(body).doesNotContainKeys("dbAvailable", "redisAvailable", "chromaAvailable", "aiProviders", "circuitBreakers");
    }

    @Test
    void competitionReadinessSummarizesA3ScoringAndCoreRequirements() {
        HealthController controller = controller();

        ResponseEntity<Map<String, Object>> response = controller.competitionReadiness();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("target")).asString().contains("中国软件杯 A3");
        assertThat(body.get("overallLevel")).asString().contains("国二冲刺");

        assertThat(body.get("scoreFocus")).isInstanceOf(Map.class);
        assertThat(body.get("coreRequirements")).isInstanceOf(Map.class);
        assertThat(body.get("awardGapActions")).isInstanceOf(Map.class);
        assertThat(body.get("demoRiskControls")).isInstanceOf(Map.class);
        assertThat(body.get("submissionChecklist")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> coreRequirements = new LinkedHashMap<>((Map<String, Object>) body.get("coreRequirements"));
        assertThat(coreRequirements).hasSize(6);
        assertThat(coreRequirements).containsKeys(
                "dialogueProfile",
                "multiAgentResourceGeneration",
                "personalizedPathAndPush",
                "multimodalTutoring",
                "learningEffectAssessment",
                "antiHallucinationAndSafety"
        );
    }

    private static HealthController controller() {
        return new HealthController(
                mock(LocalMockService.class),
                mock(AiApiConfig.class),
                mock(ProviderCircuitBreaker.class),
                mock(VectorDbService.class),
                mock(RagRetrievalService.class),
                mock(StringRedisTemplate.class),
                mock(DataSource.class)
        );
    }
}
