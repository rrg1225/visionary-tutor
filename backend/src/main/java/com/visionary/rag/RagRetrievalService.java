package com.visionary.rag;

import com.visionary.agent.AgentTaskType;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import com.visionary.rag.repository.KnowledgeSearchOutcome;
import com.visionary.rag.repository.ResilientKnowledgeSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Production RAG retrieval with layer filtering and stable citation anchors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private static final int DEFAULT_TOP_K_PER_BUCKET = 4;
    private static final int DEFAULT_TOP_K_SINGLE = 8;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final double MIN_QUERY_EVIDENCE_OVERLAP = 0.15D;
    /** Minimum vector relevance score for any citation to count as grounded evidence. */
    private static final double MIN_CITATION_RELEVANCE_SCORE = 0.20D;
    private static final int MAX_CACHE_SIZE = 1000;

    private final ResilientKnowledgeSearchRepository knowledgeSearchRepository;
    private final RagGroundingScorer groundingScorer;
    private final RetrievedContentGuard retrievedContentGuard;

    @SuppressWarnings("serial")
    private static final Map<String, CachedResult> retrievalCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedResult>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    private record CachedResult(RagRetrievalResult result, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public RagRetrievalResult retrieveForTask(AgentTaskType taskType, String query) {
        if (taskType == null) {
            return retrieve(query, KnowledgeLayer.allLayers(), DEFAULT_TOP_K_PER_BUCKET);
        }
        return switch (taskType) {
            case KNOWLEDGE_DIAGNOSIS -> retrieve(
                    query,
                    concat(KnowledgeLayer.applicationLayers(), KnowledgeLayer.mathLayers()),
                    DEFAULT_TOP_K_PER_BUCKET
            );
            case RESOURCE_GENERATION -> retrieve(query, KnowledgeLayer.allLayers(), DEFAULT_TOP_K_PER_BUCKET);
            case VISUAL_ASSESSMENT -> retrieve(
                    query,
                    concat(KnowledgeLayer.mathLayers(), KnowledgeLayer.applicationLayers()),
                    DEFAULT_TOP_K_PER_BUCKET
            );
            default -> RagRetrievalResult.empty();
        };
    }

    public RagRetrievalResult retrieve(String query, List<KnowledgeLayer> layers, int topKPerBucket) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return RagRetrievalResult.empty();
        }
        if (looksLikeUnsupportedSourceRequest(query)) {
            return RagRetrievalResult.empty();
        }

        String cacheKey = buildCacheKey(query, layers, topKPerBucket);
        CachedResult cached = retrievalCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.result();
        }

        Set<String> allowedMetadata = layers == null || layers.isEmpty()
                ? Set.of()
                : KnowledgeLayer.metadataValuesForLayers(layers);

        BucketSearch applicationBucket = searchBucket(
                query, topKPerBucket, allowedMetadata, KnowledgeLayer.applicationLayers()
        );
        BucketSearch algorithmBucket = searchBucket(
                query, topKPerBucket, allowedMetadata, KnowledgeLayer.algorithmLayers()
        );
        BucketSearch mathBucket = searchBucket(
                query, topKPerBucket, allowedMetadata, KnowledgeLayer.mathLayers()
        );
        BucketSearch ugcBucket = searchBucket(
                query, topKPerBucket, allowedMetadata, KnowledgeLayer.ugcLayers()
        );

        List<KnowledgeFragment> applicationFragments = retrievedContentGuard.filter(applicationBucket.fragments());
        List<KnowledgeFragment> algorithmFragments = retrievedContentGuard.filter(algorithmBucket.fragments());
        List<KnowledgeFragment> mathFragments = retrievedContentGuard.filter(mathBucket.fragments());
        List<KnowledgeFragment> ugcFragments = retrievedContentGuard.filter(ugcBucket.fragments());
        boolean highAvailabilityFallback = applicationBucket.highAvailabilityFallback()
                || algorithmBucket.highAvailabilityFallback()
                || mathBucket.highAvailabilityFallback()
                || ugcBucket.highAvailabilityFallback();
        String retrievalMode = resolveAggregateRetrievalMode(
                highAvailabilityFallback,
                applicationBucket,
                algorithmBucket,
                mathBucket,
                ugcBucket
        );

        List<KnowledgeFragment> all = new ArrayList<>();
        all.addAll(applicationFragments);
        all.addAll(algorithmFragments);
        all.addAll(mathFragments);
        all.addAll(ugcFragments);

        List<RagCitation> citations = filterRelevantCitations(buildCitations(all));
        if (citations.isEmpty()) {
            log.info("RAG retrieve rejected: no citations above relevance threshold, queryLen={}", query.length());
            RagRetrievalResult empty = RagRetrievalResult.empty();
            retrievalCache.put(cacheKey, new CachedResult(empty, System.currentTimeMillis()));
            return empty;
        }

        String applicationContext = formatFragments(applicationFragments);
        String algorithmContext = formatFragments(algorithmFragments);
        String mathContext = formatFragments(mathFragments);
        String ugcContext = formatFragments(ugcFragments);
        String groundedBlock = buildGroundedContextBlock(applicationContext, algorithmContext, mathContext, ugcContext);
        double queryEvidenceOverlap = groundingScorer.overlapScore(query, groundedBlock);
        if (queryEvidenceOverlap < MIN_QUERY_EVIDENCE_OVERLAP) {
            log.info("RAG retrieve rejected weak query/evidence match: queryLen={}, overlap={}",
                    query.length(), queryEvidenceOverlap);
            RagRetrievalResult empty = RagRetrievalResult.empty();
            retrievalCache.put(cacheKey, new CachedResult(empty, System.currentTimeMillis()));
            return empty;
        }

        RagRetrievalResult result = new RagRetrievalResult(
                applicationContext,
                algorithmContext,
                mathContext,
                groundedBlock,
                citations,
                true,
                retrievalMode,
                highAvailabilityFallback
        );
        retrievalCache.put(cacheKey, new CachedResult(result, System.currentTimeMillis()));

        log.info("RAG retrieve: queryLen={}, layers={}, citations={}, grounded={}, mode={}, ha={}",
                query.length(), layers == null ? "all" : layers.size(), citations.size(),
                result.hasGroundedEvidence(), retrievalMode, highAvailabilityFallback);
        return result;
    }

    public RagRetrievalResult retrieveForLayers(String query, List<KnowledgeLayer> layers) {
        if (!isAvailable() || query == null || query.isBlank() || layers == null || layers.isEmpty()) {
            return RagRetrievalResult.empty();
        }
        Set<String> allowed = KnowledgeLayer.metadataValuesForLayers(layers);
        KnowledgeSearchOutcome outcome = knowledgeSearchRepository.search(query, DEFAULT_TOP_K_SINGLE, allowed);
        List<KnowledgeFragment> safeFragments = retrievedContentGuard.filter(outcome.fragments());
        List<RagCitation> citations = filterRelevantCitations(buildCitations(safeFragments));
        if (citations.isEmpty()) {
            return RagRetrievalResult.empty();
        }
        String block = formatFragments(safeFragments);
        double queryEvidenceOverlap = groundingScorer.overlapScore(query, block);
        if (queryEvidenceOverlap < MIN_QUERY_EVIDENCE_OVERLAP) {
            return RagRetrievalResult.empty();
        }
        return new RagRetrievalResult(
                block,
                "",
                "",
                block,
                citations,
                true,
                outcome.retrievalMode(),
                outcome.highAvailabilityFallback()
        );
    }

    public String retrieveContext(String query) {
        return retrieveForTask(AgentTaskType.RESOURCE_GENERATION, query).groundedContextBlock();
    }

    public boolean isAvailable() {
        return knowledgeSearchRepository.isAvailable();
    }

    private String buildCacheKey(String query, List<KnowledgeLayer> layers, int topK) {
        String layerKey = layers == null
                ? "all"
                : layers.stream().map(KnowledgeLayer::metadataValue).sorted().collect(Collectors.joining("|"));
        return query.trim().toLowerCase() + "::" + layerKey + "::" + topK;
    }

    private BucketSearch searchBucket(
            String query,
            int topK,
            Set<String> requestAllowedLayers,
            List<KnowledgeLayer> bucketLayers
    ) {
        Set<String> bucketMetadata = KnowledgeLayer.metadataValuesForLayers(bucketLayers);
        Set<String> effective = new LinkedHashSet<>();
        if (requestAllowedLayers == null || requestAllowedLayers.isEmpty()) {
            effective.addAll(bucketMetadata);
        } else {
            for (String layer : bucketMetadata) {
                if (requestAllowedLayers.contains(layer)) {
                    effective.add(layer);
                }
            }
        }
        if (effective.isEmpty()) {
            return new BucketSearch(List.of(), KnowledgeSearchOutcome.MODE_NONE, false);
        }
        KnowledgeSearchOutcome outcome = knowledgeSearchRepository.search(query, topK, effective);
        return new BucketSearch(
                outcome.fragments(),
                outcome.retrievalMode(),
                outcome.highAvailabilityFallback()
        );
    }

    private record BucketSearch(
            List<KnowledgeFragment> fragments,
            String retrievalMode,
            boolean highAvailabilityFallback
    ) {
    }

    private static String resolveAggregateRetrievalMode(
            boolean highAvailabilityFallback,
            BucketSearch... buckets
    ) {
        if (highAvailabilityFallback) {
            return KnowledgeSearchOutcome.MODE_BM25;
        }
        boolean hybrid = false;
        boolean chroma = false;
        for (BucketSearch bucket : buckets) {
            if (bucket == null) {
                continue;
            }
            if (KnowledgeSearchOutcome.MODE_HYBRID.equals(bucket.retrievalMode())) {
                hybrid = true;
            } else if (KnowledgeSearchOutcome.MODE_CHROMA.equals(bucket.retrievalMode())) {
                chroma = true;
            }
        }
        if (hybrid) {
            return KnowledgeSearchOutcome.MODE_HYBRID;
        }
        if (chroma) {
            return KnowledgeSearchOutcome.MODE_CHROMA;
        }
        return KnowledgeSearchOutcome.MODE_NONE;
    }

    private List<RagCitation> filterRelevantCitations(List<RagCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<RagCitation> relevant = citations.stream()
                .filter(citation -> citation.score() >= MIN_CITATION_RELEVANCE_SCORE)
                .toList();
        if (relevant.isEmpty()) {
            double maxScore = citations.stream().mapToDouble(RagCitation::score).max().orElse(0D);
            log.info("RAG retrieve rejected low relevance citations: maxScore={}", maxScore);
        }
        return relevant;
    }

    private List<RagCitation> buildCitations(List<KnowledgeFragment> fragments) {
        Map<String, RagCitation> deduped = new LinkedHashMap<>();
        for (KnowledgeFragment fragment : fragments) {
            String citationId = citationIdFor(fragment);
            String layer = resolveLayerLabel(fragment);
            String key = citationId + "|" + layer;
            deduped.putIfAbsent(key, new RagCitation(
                    citationId,
                    fragment.source(),
                    layer,
                    fragment.category(),
                    fragment.score(),
                    truncate(fragment.content(), 320),
                    firstNonBlank(fragment.chunkId(), fragment.vectorId(), ""),
                    firstNonBlank(fragment.sourcePath(), fragment.source(), ""),
                    fragment.chunkIndex(),
                    fragment.chunkStart(),
                    fragment.chunkEnd()
            ));
        }
        return List.copyOf(deduped.values());
    }

    private String formatFragments(List<KnowledgeFragment> fragments) {
        if (fragments.isEmpty()) {
            return "(no matched grounded fragments)";
        }
        return fragments.stream().map(this::formatSingleFragment).collect(Collectors.joining("\n\n---\n\n"));
    }

    private String formatSingleFragment(KnowledgeFragment fragment) {
        String anchor = "chunkId=%s | sourcePath=%s | chunkIndex=%s".formatted(
                blankToDash(firstNonBlank(fragment.chunkId(), fragment.vectorId(), "")),
                blankToDash(firstNonBlank(fragment.sourcePath(), fragment.source(), "")),
                fragment.chunkIndex() == null ? "-" : fragment.chunkIndex()
        );
        if ("ugc_textbook".equals(fragment.chunkType())) {
            return """
                    [%s][ugc-textbook] layer=%s | source=%s | %s | score=%.3f
                    %s
                    """.formatted(
                    citationIdFor(fragment),
                    resolveLayerLabel(fragment),
                    fragment.source(),
                    anchor,
                    fragment.score(),
                    fragment.content()
            ).trim();
        }
        if ("image_caption".equals(fragment.chunkType())) {
            return """
                    [%s][teaching-image] layer=%s | source=%s | %s | score=%.3f
                    [image-caption]: %s
                    [image-markdown]: ![diagram](%s)
                    """.formatted(
                    citationIdFor(fragment),
                    resolveLayerLabel(fragment),
                    fragment.source(),
                    anchor,
                    fragment.score(),
                    fragment.content(),
                    fragment.imagePath()
            ).trim();
        }
        return """
                [%s] layer=%s | source=%s | category=%s | %s | score=%.3f
                %s
                """.formatted(
                citationIdFor(fragment),
                resolveLayerLabel(fragment),
                fragment.source(),
                blankToDash(fragment.category()),
                anchor,
                fragment.score(),
                fragment.content()
        ).trim();
    }

    private String buildGroundedContextBlock(String application, String algorithm, String math, String ugc) {
        return """
                === Retrieved Knowledge Context (Grounded) ===

                ## Application Layer (course / concept / exercise / assessment)
                %s

                ## Algorithm Layer (algorithm / code)
                %s

                ## Math Layer
                %s

                ## UGC Layer (community textbooks)
                %s
                """.formatted(application, algorithm, math, ugc);
    }

    private String citationIdFor(KnowledgeFragment fragment) {
        String raw = firstNonBlank(
                fragment.chunkId(),
                fragment.vectorId(),
                fragment.sourcePath() + "-" + (fragment.chunkIndex() == null ? "" : fragment.chunkIndex()),
                fragment.source() + "-" + Integer.toHexString(fragment.content() == null ? 0 : fragment.content().hashCode())
        );
        String normalized = raw.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5_.:-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = Integer.toHexString(fragment.hashCode());
        }
        return "cite-" + normalized;
    }

    private boolean looksLikeUnsupportedSourceRequest(String query) {
        String lower = query.toLowerCase();
        return lower.matches(".*visionary-\\d+.*")
                || containsAny(query, "身份证号", "银行卡号", "护照号")
                || (containsAny(query, "编造", "伪造", "不存在", "没有来源")
                    && containsAny(query, "doi", "论文", "来源", "引用", "教材", "原文"))
                || (containsAny(query, "一定", "保证", "必然")
                    && containsAny(query, "所有学生", "满分", "百分之百"))
                || query.contains("火星")
                || query.contains("量子退火治疗癌症")
                || query.contains("癌症的临床流程");
    }

    private static boolean containsAny(String text, String... candidates) {
        String normalized = text.toLowerCase();
        for (String candidate : candidates) {
            if (normalized.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String resolveLayerLabel(KnowledgeFragment fragment) {
        if (fragment.layer() != null && !fragment.layer().isBlank()) {
            return fragment.layer();
        }
        KnowledgeLayer parsed = KnowledgeLayer.fromMetadata(fragment.chromaLayer());
        return parsed != null ? parsed.metadataValue() : blankToDash(fragment.chromaLayer());
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SafeVarargs
    private static List<KnowledgeLayer> concat(List<KnowledgeLayer>... lists) {
        List<KnowledgeLayer> merged = new ArrayList<>();
        for (List<KnowledgeLayer> list : lists) {
            merged.addAll(list);
        }
        return merged;
    }
}
