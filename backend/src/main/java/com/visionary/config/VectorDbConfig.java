package com.visionary.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Chroma 向量数据库配置。
 * <p>Java 端通过 Langchain4j {@code ChromaEmbeddingStore} 连接 Chroma HTTP 服务（默认 {@code http://localhost:8000}），
 * 检索 Python {@code document_processor.py} 写入的单一全局 Collection {@value #GLOBAL_COLLECTION_NAME}。</p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "vector.db")
public class VectorDbConfig {

    /** 与 Python 入库脚本、Langchain4j Bean 共用的全局 Collection 名（不可改为分层 Collection）。 */
    public static final String GLOBAL_COLLECTION_NAME = "visionary_global_knowledge";

    /**
     * 是否启用向量检索。
     */
    private boolean enabled = false;

    /**
     * 数据库类型，当前固定为 chroma。
     */
    private String type = "chroma";

    /**
     * Chroma HTTP 服务主机（与 {@link #port} 合成 {@link #getChromaBaseUrl()}）。
     */
    private String host = "localhost";

    /**
     * Chroma HTTP 服务端口（默认 8000 → {@code http://localhost:8000}）。
     */
    private int port = 8000;

    /**
     * Chroma REST API 版本：V1 或 V2（Chroma 0.7+ 须用 V2，与 Python HttpClient 一致）。
     */
    private String chromaApiVersion = "V2";

    /**
     * Chroma V2 租户名（须与 Python {@code CHROMA_TENANT} 一致）。
     */
    private String tenantName = "default_tenant";

    /**
     * Chroma V2 数据库名（须与 Python {@code CHROMA_DATABASE} 一致）。
     */
    private String databaseName = "default_database";

    /**
     * 统一全局 Collection 名称（与 Python 强制写入的 collection 一致）。
     */
    private String collectionName = GLOBAL_COLLECTION_NAME;

    /**
     * 知识库根路径（相对于项目根或可配置的绝对路径）。
     */
    private String knowledgeBasePath = "ai_engine/knowledge_base/processed";

    /**
     * 嵌入模型名称，必须与 Python {@code document_processor.py} 一致。
     */
    private String embeddingModel = "all-MiniLM-L6-v2";

    /**
     * Embedding provider for Java-side query vectors.
     * local: Langchain4j all-MiniLM-L6-v2.
     * dashscope: OpenAI-compatible DashScope embeddings API, useful for Chinese RAG.
     */
    private String embeddingProvider = "local";

    /**
     * all-MiniLM-L6-v2 向量维度。
     */
    private int vectorDimension = 384;

    /**
     * 默认 Top-K 检索数量（每层）。
     */
    private int defaultTopK = 5;

    /**
     * 相似度阈值，低于此值的片段将被过滤。
     */
    private double similarityThreshold = 0.0;

    /**
     * Minimum retrieved text length. Short bibliography/title-only chunks are filtered out.
     */
    private int minContentLength = 40;

    /**
     * Lightweight lexical rerank after vector retrieval, useful for Chinese course terms and code symbols.
     */
    private boolean keywordRerankEnabled = true;

    /**
     * HTTP 连接超时（秒）。
     */
    private int connectTimeoutSeconds = 10;

    /**
     * HTTP 读取超时（秒）。
     */
    private int readTimeoutSeconds = 30;

    /**
     * Chroma 向量检索超时（毫秒），超时后切换 BM25 高可用模式。
     */
    private long chromaQueryTimeoutMs = 3000L;

    /**
     * 是否启用 Chroma 失败后的 BM25 内存降级。
     */
    private boolean bm25FallbackEnabled = true;

    /**
     * Chroma 可用时是否并行执行 BM25 并通过 RRF 融合（提升长难句关键词召回）。
     */
    private boolean hybridRetrievalEnabled = true;

    /**
     * 混合检索每路候选倍数（实际候选数 = topK * multiplier）。
     */
    private int hybridCandidateMultiplier = 2;

    /**
     * RRF 融合常数 k（经典默认 60）。
     */
    private int rrfK = 60;

    /**
     * BM25 降级路径 SLA（毫秒），仅用于监控告警。
     */
    private long bm25FallbackSlaMs = 50L;

    /**
     * BM25 启动时加载的最大 Markdown 文档数。
     */
    private int bm25MaxDocuments = 800;

    public String getChromaBaseUrl() {
        return "http://" + host.trim() + ":" + port;
    }

    public boolean isChroma() {
        return "chroma".equalsIgnoreCase(type);
    }

    public boolean isChromaApiV2() {
        return "V2".equalsIgnoreCase(chromaApiVersion);
    }

    public boolean isAllMiniLmL6V2() {
        return embeddingModel != null
                && embeddingModel.toLowerCase().contains("all-minilm-l6-v2");
    }

    public boolean isLocalEmbeddingProvider() {
        return embeddingProvider == null
                || embeddingProvider.isBlank()
                || "local".equalsIgnoreCase(embeddingProvider);
    }

    public boolean isDashScopeEmbeddingProvider() {
        return "dashscope".equalsIgnoreCase(embeddingProvider)
                || "qwen".equalsIgnoreCase(embeddingProvider);
    }

    public boolean isConfigured() {
        return host != null && !host.isBlank()
                && port > 0
                && GLOBAL_COLLECTION_NAME.equals(collectionName);
    }

    @PostConstruct
    void enforceGlobalCollectionAlignment() {
        if (!GLOBAL_COLLECTION_NAME.equals(collectionName)) {
            log.warn(
                    "vector.db.collection-name='{}' 与 Python 入库不一致，已强制改为 '{}'",
                    collectionName,
                    GLOBAL_COLLECTION_NAME
            );
            collectionName = GLOBAL_COLLECTION_NAME;
        }
        if (enabled && isChroma()) {
            log.info(
                    "Chroma RAG 已启用: baseUrl={}, collection={}, tenant={}, database={}, api={}",
                    getChromaBaseUrl(),
                    collectionName,
                    tenantName,
                    databaseName,
                    chromaApiVersion
            );
        }
    }
}
