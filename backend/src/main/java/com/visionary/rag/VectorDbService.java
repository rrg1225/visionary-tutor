package com.visionary.rag;

import com.visionary.config.VectorDbConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 统一全局向量检索服务。
 * <p>连接单一 Chroma Collection {@link com.visionary.config.VectorDbConfig#GLOBAL_COLLECTION_NAME}，
 * 服务于所有 Agent 任务。</p>
 */
@Slf4j
@Service
public class VectorDbService {

    private final VectorDbConfig config;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChromaV2Client chromaV2Client;
    private final DashScopeRerankClient rerankClient;

    public VectorDbService(
            VectorDbConfig config,
            @Autowired(required = false) EmbeddingStore<TextSegment> embeddingStore,
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) ChromaV2Client chromaV2Client,
            @Autowired(required = false) DashScopeRerankClient rerankClient
    ) {
        this.config = config;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chromaV2Client = chromaV2Client;
        this.rerankClient = rerankClient;
    }

    /**
     * 统一检索接口：返回匹配的文档片段列表（无元数据过滤）。
     */
    public List<KnowledgeFragment> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 带元数据 Layer 过滤的检索。优先使用 Chroma {@link Filter}；检索后再次在应用层校验 layer，防止幻觉。
     *
     * @param allowedLayers 允许的教学层 metadata 值（如 course_layer）；null 或空表示不过滤
     */
    public List<KnowledgeFragment> search(String query, int topK, Set<String> allowedLayers) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            if (config.isChromaApiV2() && chromaV2Client != null) {
                List<KnowledgeFragment> fragments = chromaV2Client.query(queryEmbedding, Math.max(topK * 2, topK), allowedLayers).stream()
                        .filter(fragment -> fragment.score() >= config.getSimilarityThreshold())
                        .filter(fragment -> matchesLayer(fragment, allowedLayers))
                        .toList();
                return qualityFilterAndRerank(query, fragments, topK);
            }

            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(Math.max(1, topK));

            Filter metadataFilter = buildLayerFilter(allowedLayers);
            if (metadataFilter != null) {
                requestBuilder.filter(metadataFilter);
            }

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());

            List<KnowledgeFragment> fragments = result.matches().stream()
                    .filter(match -> match.score() >= config.getSimilarityThreshold())
                    .map(this::toFragment)
                    .filter(fragment -> matchesLayer(fragment, allowedLayers))
                    .toList();
            return qualityFilterAndRerank(query, fragments, topK);

        } catch (Exception e) {
            log.error("全局向量检索失败: query='{}', error={}", truncate(query, 80), e.getMessage(), e);
            throw new KnowledgeRetrievalException(
                    "Vector retrieval failed for global collection",
                    query,
                    config.getCollectionName(),
                    e
            );
        }
    }

    /**
     * 摄取单个 Document（用于图像字幕等）：计算 embedding 并写入 Chroma。
     */
    public void upsert(Document document) {
        if (!isAvailable() || document == null) {
            return;
        }
        try {
            var metadata = document.metadata().copy();
            TextSegment segment = TextSegment.from(document.text(), metadata);
            Embedding embedding = embeddingModel.embed(segment).content();
            if (config.isChromaApiV2() && chromaV2Client != null) {
                chromaV2Client.upsert(document, embedding);
                log.debug("VectorDb V2 upsert 瀹屾垚: chunk_type={}, image_path={}",
                        metadataString(metadata, "chunk_type", ""), metadataString(metadata, "image_path", ""));
                return;
            }

            embeddingStore.add(embedding, segment);
            log.debug("VectorDb upsert 完成: chunk_type={}, image_path={}",
                    metadataString(metadata, "chunk_type", ""), metadataString(metadata, "image_path", ""));
        } catch (Exception e) {
            log.error("VectorDb upsert 失败: {}", e.getMessage(), e);
            throw new RuntimeException("Vector upsert failed", e);
        }
    }

    public void deleteBySourcePrefix(String sourcePrefix) {
        if (!isAvailable() || sourcePrefix == null || sourcePrefix.isBlank()) {
            return;
        }
        try {
            if (config.isChromaApiV2() && chromaV2Client != null) {
                if (sourcePrefix.startsWith("ugc-textbook:")) {
                    String textbookId = sourcePrefix.substring("ugc-textbook:".length());
                    chromaV2Client.deleteByMetadataEquals("textbook_id", textbookId);
                } else {
                    chromaV2Client.deleteByMetadataEquals("source", sourcePrefix);
                }
            }
        } catch (Exception e) {
            log.warn("Vector delete failed for sourcePrefix={}: {}", sourcePrefix, e.getMessage());
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.isConfigured()
                && config.isChroma()
                && (config.isChromaApiV2()
                    ? chromaV2Client != null && chromaV2Client.isAvailable()
                    : embeddingStore != null)
                && embeddingModel != null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 知识片段记录（适配新扁平结构，支持图像字幕元数据）。
     */
    public record KnowledgeFragment(
            String content,
            String category,
            String source,
            double score,
            String chunkType,
            String imagePath,
            String layer,
            String chromaLayer,
            String vectorId,
            String chunkId,
            String sourcePath,
            Integer chunkIndex,
            Integer chunkStart,
            Integer chunkEnd
    ) {
    }

    private KnowledgeFragment toFragment(dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        String content = segment.text();
        String source = metadataString(segment.metadata(), "source", "unknown");
        String category = metadataString(segment.metadata(), "category", "");
        String chunkType = metadataString(segment.metadata(), "chunk_type", "");
        String imagePath = metadataString(segment.metadata(), "image_path", "");
        String layer = metadataString(segment.metadata(), "layer", "");
        String chromaLayer = metadataString(segment.metadata(), "chroma_layer", "");
        String vectorId = metadataString(segment.metadata(), "vector_id", "");
        String chunkId = metadataString(segment.metadata(), "chunk_id", "");
        String sourcePath = metadataString(segment.metadata(), "source_path", "");
        Integer chunkIndex = metadataInteger(segment.metadata(), "chunk_index");
        Integer chunkStart = metadataInteger(segment.metadata(), "chunk_start");
        Integer chunkEnd = metadataInteger(segment.metadata(), "chunk_end");
        return new KnowledgeFragment(
                content, category, source, match.score(), chunkType, imagePath, layer, chromaLayer,
                vectorId, chunkId, sourcePath, chunkIndex, chunkStart, chunkEnd
        );
    }

    private static String metadataString(dev.langchain4j.data.document.Metadata metadata, String key, String defaultValue) {
        String value = metadata.getString(key);
        return value == null ? defaultValue : value;
    }

    private static Integer metadataInteger(dev.langchain4j.data.document.Metadata metadata, String key) {
        String value = metadata.getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Filter buildLayerFilter(Set<String> allowedLayers) {
        if (allowedLayers == null || allowedLayers.isEmpty()) {
            return null;
        }
        Filter combined = null;
        for (String layer : allowedLayers) {
            Filter equalsLayer = metadataKey("layer").isEqualTo(layer);
            combined = combined == null ? equalsLayer : combined.or(equalsLayer);
        }
        return combined;
    }

    private boolean matchesLayer(KnowledgeFragment fragment, Set<String> allowedLayers) {
        if (allowedLayers == null || allowedLayers.isEmpty()) {
            return true;
        }
        if (fragment.layer() != null && allowedLayers.contains(fragment.layer())) {
            return true;
        }
        if (fragment.chromaLayer() != null && allowedLayers.contains(fragment.chromaLayer())) {
            return true;
        }
        if (fragment.category() != null) {
            String category = fragment.category().toLowerCase();
            for (String layer : allowedLayers) {
                if (category.startsWith(layer.toLowerCase()) || category.contains("/" + layer.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<KnowledgeFragment> qualityFilterAndRerank(String query, List<KnowledgeFragment> fragments, int topK) {
        if (fragments == null || fragments.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeFragment> filtered = fragments.stream()
                .filter(this::hasEnoughTeachingContent)
                .toList();
        if (!config.isKeywordRerankEnabled()) {
            return filtered.stream().limit(Math.max(1, topK)).toList();
        }
        if (rerankClient != null && rerankClient.isConfigured()) {
            try {
                return rerankClient.rerank(query, filtered, topK);
            } catch (Exception e) {
                log.warn("DashScope rerank failed, falling back to keyword rerank: {}", e.getMessage());
            }
        }
        Set<String> queryTerms = tokenize(query);
        return filtered.stream()
                .sorted(Comparator
                        .comparingDouble((KnowledgeFragment fragment) -> rerankScore(fragment, queryTerms))
                        .reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private boolean hasEnoughTeachingContent(KnowledgeFragment fragment) {
        if (fragment == null || fragment.content() == null) {
            return false;
        }
        String content = fragment.content().trim();
        if (content.length() < Math.max(0, config.getMinContentLength())) {
            return false;
        }
        String lower = content.toLowerCase();
        return !(lower.startsWith("isbn")
                || lower.startsWith("copyright")
                || lower.contains("all rights reserved"));
    }

    private double rerankScore(KnowledgeFragment fragment, Set<String> queryTerms) {
        double score = fragment.score();
        if (queryTerms.isEmpty()) {
            return score;
        }
        Set<String> docTerms = tokenize(fragment.content() + " " + fragment.source() + " " + fragment.category());
        int overlap = 0;
        for (String term : queryTerms) {
            if (docTerms.contains(term)) {
                overlap++;
            }
        }
        return score + overlap * 0.05;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9_+#.-]+", " ");
        Set<String> terms = new HashSet<>();
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }
}
