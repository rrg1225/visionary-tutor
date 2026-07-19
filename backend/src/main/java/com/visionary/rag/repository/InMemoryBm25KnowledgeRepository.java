package com.visionary.rag.repository;

import com.visionary.config.VectorDbConfig;
import com.visionary.rag.KnowledgeLayer;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Slf4j
@Repository
public class InMemoryBm25KnowledgeRepository implements KnowledgeSearchRepository {

    private final VectorDbConfig config;
    private final Bm25Index index = new Bm25Index();
    private volatile boolean ready;

    public InMemoryBm25KnowledgeRepository(VectorDbConfig config) {
        this.config = config;
    }

    @PostConstruct
    void loadCorpus() {
        int loaded = 0;
        loaded += loadFromFilesystem();
        if (loaded == 0) {
            loaded += loadClasspathFallback();
        }
        index.finalizeIndex();
        ready = index.size() > 0;
        log.info("BM25 HA corpus loaded: documents={}, ready={}", index.size(), ready);
    }

    @Override
    public String backendName() {
        return KnowledgeSearchOutcome.MODE_BM25;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public List<KnowledgeFragment> search(String query, int topK, Set<String> allowedLayers) {
        if (!ready) {
            return Collections.emptyList();
        }
        return index.search(query, topK, allowedLayers);
    }

    private int loadFromFilesystem() {
        if (config.getKnowledgeBasePath() == null || config.getKnowledgeBasePath().isBlank()) {
            return 0;
        }
        Path basePath = Paths.get(config.getKnowledgeBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            log.warn("BM25 corpus path missing, will try classpath fallback: {}", basePath);
            return 0;
        }

        int maxFiles = Math.max(50, config.getBm25MaxDocuments());
        AtomicInteger counter = new AtomicInteger();
        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .limit(maxFiles)
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            if (content.length() < Math.max(20, config.getMinContentLength())) {
                                return;
                            }
                            String layer = resolveLayerFromPath(basePath, path);
                            String source = basePath.relativize(path).toString().replace('\\', '/');
                            String docId = "bm25-" + Integer.toHexString(source.hashCode());
                            index.addDocument(docId, content, source, layer, layer);
                            counter.incrementAndGet();
                        } catch (IOException e) {
                            log.debug("Skip BM25 doc {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("BM25 filesystem corpus scan failed: {}", e.getMessage());
            return 0;
        }
        return counter.get();
    }

    private int loadClasspathFallback() {
        try {
            ClassPathResource resource = new ClassPathResource("rag-fallback/corpus.md");
            if (!resource.exists()) {
                return 0;
            }
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String chunk : content.split("(?m)^---$")) {
                String trimmed = chunk.trim();
                if (trimmed.length() < 40) {
                    continue;
                }
                String docId = "bm25-fallback-" + Integer.toHexString(trimmed.hashCode());
                index.addDocument(docId, trimmed, "rag-fallback/corpus.md", "concept_layer", "concept_layer");
            }
            return index.size();
        } catch (IOException e) {
            log.warn("BM25 classpath fallback unavailable: {}", e.getMessage());
            return 0;
        }
    }

    private static String resolveLayerFromPath(Path basePath, Path file) {
        Path relativeParent = basePath.relativize(file.getParent());
        String folder = relativeParent == null ? "" : relativeParent.toString().replace('\\', '/');
        if (folder.contains("math")) {
            return KnowledgeLayer.MATH.metadataValue();
        }
        if (folder.contains("algorithm")) {
            return KnowledgeLayer.ALGORITHM.metadataValue();
        }
        if (folder.contains("code")) {
            return KnowledgeLayer.CODE.metadataValue();
        }
        if (folder.contains("ugc")) {
            return KnowledgeLayer.UGC.metadataValue();
        }
        if (folder.contains("exercise")) {
            return KnowledgeLayer.EXERCISE.metadataValue();
        }
        if (folder.contains("assessment")) {
            return KnowledgeLayer.ASSESSMENT.metadataValue();
        }
        if (folder.contains("course")) {
            return KnowledgeLayer.COURSE.metadataValue();
        }
        return KnowledgeLayer.CONCEPT.metadataValue();
    }
}
