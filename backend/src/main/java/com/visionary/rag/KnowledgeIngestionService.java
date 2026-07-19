package com.visionary.rag;

import com.visionary.config.VectorDbConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java 原生知识库摄取服务。
 * 负责将 ai_engine/knowledge_base/processed/ 下的全部预切分 .md 文件摄取到统一全局 Collection。
 */
@Slf4j
@Service
public class KnowledgeIngestionService {

    private final VectorDbConfig config;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    public KnowledgeIngestionService(
            VectorDbConfig config,
            @Autowired(required = false) EmbeddingStore<TextSegment> embeddingStore,
            @Autowired(required = false) EmbeddingModel embeddingModel
    ) {
        this.config = config;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 批量摄取整个知识库（递归扫描 processed/ 目录）。
     * 由于文件已完美预切分，不再执行二次切分。
     */
    public int ingestKnowledgeBase() {
        if (!isAvailable()) {
            log.warn("Knowledge ingestion skipped because vector store or embedding model is unavailable.");
            return 0;
        }

        Path basePath = Paths.get(config.getKnowledgeBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            log.error("知识库路径不存在或不是目录: {}", basePath);
            return 0;
        }

        DocumentParser parser = new TextDocumentParser();
        List<TextSegment> allSegments = new ArrayList<>();
        List<Embedding> allEmbeddings = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(basePath)) {
            List<Path> mdFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .toList();

            log.info("发现 {} 个预切分 Markdown 文件，开始摄取...", mdFiles.size());

            for (Path file : mdFiles) {
                try {
                    Document doc = FileSystemDocumentLoader.loadDocument(file, parser);

                    // 计算相对路径作为 category 元数据（例如 "计算机视觉算法与应用中文版-xxx"）
                    String relativeDir = basePath.relativize(file.getParent()).toString().replace("\\", "/");
                    String fileName = file.getFileName().toString();

                    var metadata = doc.metadata().copy();
                    metadata.put("source", fileName);
                    metadata.put("category", relativeDir);
                    metadata.put("full_path", file.toString());
                    String inferredLayer = inferLayerFromPath(relativeDir);
                    if (inferredLayer != null) {
                        metadata.put("layer", inferredLayer);
                        metadata.put("chroma_layer", chromaBucketForLayer(inferredLayer));
                    }

                    TextSegment segment = TextSegment.from(doc.text(), metadata);
                    Embedding embedding = embeddingModel.embed(segment).content();

                    allSegments.add(segment);
                    allEmbeddings.add(embedding);

                } catch (Exception e) {
                    log.warn("单个文件摄取失败: {}, error={}", file, e.getMessage());
                }
            }

            if (!allSegments.isEmpty()) {
                embeddingStore.addAll(allEmbeddings, allSegments);
                log.info("全局知识库摄取完成: 共 {} 个片段已写入 Collection [{}]", allSegments.size(), config.getCollectionName());
            }
            return allSegments.size();

        } catch (IOException e) {
            log.error("遍历知识库目录失败", e);
            throw new RuntimeException("Knowledge base ingestion failed", e);
        }
    }

    private static String inferLayerFromPath(String relativeDir) {
        if (relativeDir == null || relativeDir.isBlank()) {
            return null;
        }
        String first = relativeDir.split("[/\\\\]")[0].toLowerCase();
        for (KnowledgeLayer layer : KnowledgeLayer.values()) {
            if (layer.metadataValue().equals(first)) {
                return layer.metadataValue();
            }
        }
        return null;
    }

    private boolean isAvailable() {
        return config.isEnabled()
                && config.isConfigured()
                && config.isChroma()
                && embeddingStore != null
                && embeddingModel != null;
    }

    private static String chromaBucketForLayer(String layerMetadata) {
        KnowledgeLayer layer = KnowledgeLayer.fromMetadata(layerMetadata);
        return layer != null ? layer.chromaBucket() : "application_layer";
    }
}
