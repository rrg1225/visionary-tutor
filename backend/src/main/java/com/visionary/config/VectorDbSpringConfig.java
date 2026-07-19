package com.visionary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.rag.DashScopeEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Duration;

/**
 * Langchain4j vector model and Chroma store configuration.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "vector.db", name = "enabled", havingValue = "true")
public class VectorDbSpringConfig {

    @Bean
    @Conditional(DashScopeEmbeddingAvailableCondition.class)
    public EmbeddingModel dashScopeEmbeddingModel(
            VectorDbConfig config,
            AiApiConfig aiApiConfig,
            ObjectMapper objectMapper
    ) {
        log.info(
                "[vector-db] 注册 DashScope EmbeddingModel: model={}, dimension={}",
                config.getEmbeddingModel(),
                config.getVectorDimension()
        );
        return new DashScopeEmbeddingModel(
                objectMapper,
                aiApiConfig.getQwenBaseUrl(),
                aiApiConfig.getQwenVlMaxKey(),
                config.getEmbeddingModel(),
                config.getVectorDimension(),
                config.getConnectTimeoutSeconds()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "vector.db", name = "embedding-provider", havingValue = "local", matchIfMissing = true)
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel localEmbeddingModel(VectorDbConfig config) {
        if (!config.isAllMiniLmL6V2()) {
            throw new IllegalStateException(
                    "Unsupported local embedding model: " + config.getEmbeddingModel()
                            + ". Only all-MiniLM-L6-v2 is supported."
            );
        }
        log.info("[vector-db] 注册本地 all-MiniLM-L6-v2 EmbeddingModel");
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    @ConditionalOnProperty(prefix = "vector.db", name = "chroma-api-version", havingValue = "V1")
    public EmbeddingStore<TextSegment> chromaEmbeddingStore(VectorDbConfig config) {
        return ChromaEmbeddingStore.builder()
                .baseUrl(config.getChromaBaseUrl())
                .collectionName(VectorDbConfig.GLOBAL_COLLECTION_NAME)
                .timeout(Duration.ofSeconds(Math.max(1, config.getReadTimeoutSeconds())))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    static final class DashScopeEmbeddingAvailableCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String provider = context.getEnvironment().getProperty("vector.db.embedding-provider", "local");
            boolean dashScopeProvider = "dashscope".equalsIgnoreCase(provider) || "qwen".equalsIgnoreCase(provider);
            if (!dashScopeProvider) {
                return false;
            }
            if (isDashScopeKeyPresent(context.getEnvironment())) {
                return true;
            }
            log.warn(
                    "[vector-db] embedding-provider={} 但 DashScope API Key 未配置，"
                            + "跳过云端 EmbeddingModel；资源推荐将降级为词项匹配",
                    provider
            );
            return false;
        }

        private static boolean isDashScopeKeyPresent(org.springframework.core.env.Environment environment) {
            return firstNonBlank(
                    environment.getProperty("ai.api.dashScopeKey"),
                    environment.getProperty("ai.api.qwenVlMaxKey"),
                    environment.getProperty("DASHSCOPE_API_KEY"),
                    environment.getProperty("QWEN_VL_MAX_KEY")
            ) != null;
        }

        private static String firstNonBlank(String... candidates) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
