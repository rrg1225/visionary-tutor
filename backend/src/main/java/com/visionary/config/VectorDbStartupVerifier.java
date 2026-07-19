package com.visionary.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionary.rag.ChromaV2Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

/**
 * 启动时校验 Chroma Collection 向量维度与 {@link VectorDbConfig#getVectorDimension()} 一致，
 * 防止 embedding 模型切换后 silent recall 退化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vector.db", name = "enabled", havingValue = "true")
public class VectorDbStartupVerifier implements ApplicationRunner {

    private final VectorDbConfig vectorDbConfig;

    @Autowired(required = false)
    private ChromaV2Client chromaV2Client;

    @Override
    public void run(ApplicationArguments args) {
        if (!vectorDbConfig.isChroma() || !vectorDbConfig.isConfigured()) {
            log.info("[vector-db] 启动校验已跳过（Chroma 未配置）");
            return;
        }
        if (!vectorDbConfig.isChromaApiV2() || chromaV2Client == null) {
            log.warn("[vector-db] 启动维度校验已跳过（Chroma V2 客户端不可用）");
            return;
        }

        verifyCollectionDimension();
    }

    private void verifyCollectionDimension() {
        int expected = vectorDbConfig.getVectorDimension();
        JsonNode collection;
        try {
            collection = chromaV2Client.ensureCollectionExists();
        } catch (Exception e) {
            if (isChromaUnreachable(e)) {
                log.warn(
                        "[vector-db] Chroma 未启动或不可达: baseUrl={}, collection={}。"
                                + "跳过启动维度校验；向量检索将降级为 BM25 词项匹配。"
                                + "如需完整 RAG，请先启动 Chroma（默认端口 8000）。",
                        vectorDbConfig.getChromaBaseUrl(),
                        vectorDbConfig.getCollectionName()
                );
                return;
            }
            if (ChromaV2Client.isCollectionMissing(e)) {
                log.warn(
                        "[vector-db] Chroma collection '{}' 不存在且自动创建失败，跳过维度校验；RAG 将降级为 BM25。"
                                + "请确认 Chroma 已启动，或运行 ai_engine/document_processor.py 入库。",
                        vectorDbConfig.getCollectionName()
                );
                return;
            }
            log.error(
                    "[vector-db] 无法读取 Chroma collection metadata: baseUrl={}, collection={}, error={}",
                    vectorDbConfig.getChromaBaseUrl(),
                    vectorDbConfig.getCollectionName(),
                    e.getMessage()
            );
            throw new IllegalStateException("Vector DB startup verification failed: cannot read Chroma metadata", e);
        }

        Integer actual = resolveDimension(collection);
        JsonNode metadata = collection.path("metadata");
        if (actual == null) {
            log.warn(
                    "[vector-db] Chroma collection 尚未写入向量或未暴露 dimension 字段，跳过维度比对（expected={}）",
                    expected
            );
            return;
        }

        if (actual != expected) {
            log.error(
                    "[vector-db] Chroma 向量维度不匹配: expected={}, actual={}, collection={}, baseUrl={}, "
                            + "embeddingProvider={}, metadata={}",
                    expected,
                    actual,
                    vectorDbConfig.getCollectionName(),
                    vectorDbConfig.getChromaBaseUrl(),
                    vectorDbConfig.getEmbeddingProvider(),
                    metadata.isMissingNode() || metadata.isNull() ? "{}" : metadata.toString()
            );
            throw new IllegalStateException(
                    "Chroma vector dimension mismatch: configured=" + expected + ", collection=" + actual
            );
        }

        log.info(
                "[vector-db] Chroma 维度校验通过: dimension={}, provider={}, similarityThreshold={}",
                expected,
                vectorDbConfig.getEmbeddingProvider(),
                vectorDbConfig.getSimilarityThreshold()
        );
    }

    private static Integer resolveDimension(JsonNode collection) {
        JsonNode dimensionNode = collection.get("dimension");
        if (dimensionNode != null && dimensionNode.isNumber()) {
            return dimensionNode.asInt();
        }

        JsonNode metadata = collection.path("metadata");
        if (metadata.isObject()) {
            Integer fromMetadata = readIntMetadata(metadata, "vector_dimension");
            if (fromMetadata != null) {
                return fromMetadata;
            }
            return readIntMetadata(metadata, "dimension");
        }
        return null;
    }

    private static Integer readIntMetadata(JsonNode metadata, String key) {
        JsonNode value = metadata.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isChromaUnreachable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof ClosedChannelException) {
                return true;
            }
        }
        String message = error.getMessage();
        return message != null
                && (message.contains("ConnectException")
                || message.contains("Connection refused")
                || message.contains("Failed to connect"));
    }
}
