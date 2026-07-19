package com.visionary.rag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.VectorDbConfig;
import com.visionary.rag.KnowledgeLayer;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Bm25GoldSetEvaluationTest {

    @Test
    void trackedCorpusMeetsGoldSetTopKRecallFloor() throws Exception {
        Path projectRoot = locateProjectRoot();
        Path corpusRoot = projectRoot.resolve("ai_engine/knowledge_base/processed");
        long corpusDocuments;
        try (var paths = Files.walk(corpusRoot)) {
            corpusDocuments = paths.filter(path -> path.toString().endsWith(".md")).count();
        }
        assumeTrue(corpusDocuments >= 100,
                "Full derived RAG benchmark corpus is intentionally not distributed in the public repository");
        VectorDbConfig config = new VectorDbConfig();
        config.setKnowledgeBasePath(corpusRoot.toString());
        config.setBm25MaxDocuments(20_000);
        config.setMinContentLength(40);
        InMemoryBm25KnowledgeRepository repository = new InMemoryBm25KnowledgeRepository(config);
        repository.loadCorpus();

        ObjectMapper objectMapper = new ObjectMapper();
        List<JsonNode> supportedCases = new ArrayList<>();
        for (String line : Files.readAllLines(
                projectRoot.resolve("ai_engine/eval_sets/rag_gold_qa.jsonl")
        )) {
            if (!line.isBlank()) {
                JsonNode item = objectMapper.readTree(line);
                if (!item.path("should_refuse").asBoolean(false)) {
                    supportedCases.add(item);
                }
            }
        }

        List<String> recallMisses = new ArrayList<>();
        List<String> citationMisses = new ArrayList<>();
        int recalled = 0;
        int citationCorrect = 0;
        for (JsonNode item : supportedCases) {
            String query = item.path("query").asText();
            List<KnowledgeFragment> hits = new ArrayList<>();
            hits.addAll(repository.search(
                    query, 4, KnowledgeLayer.metadataValuesForLayers(KnowledgeLayer.applicationLayers())
            ));
            hits.addAll(repository.search(
                    query, 4, KnowledgeLayer.metadataValuesForLayers(KnowledgeLayer.algorithmLayers())
            ));
            hits.addAll(repository.search(
                    query, 4, KnowledgeLayer.metadataValuesForLayers(KnowledgeLayer.mathLayers())
            ));
            hits.addAll(repository.search(
                    query, 4, KnowledgeLayer.metadataValuesForLayers(KnowledgeLayer.ugcLayers())
            ));
            String evidence = hits.stream()
                    .map(hit -> hit.content() + " " + hit.source())
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase(Locale.ROOT);
            int expectedTermCount = item.path("expected_terms").size();
            int matchedTerms = 0;
            for (JsonNode term : item.path("expected_terms")) {
                if (evidence.contains(term.asText().toLowerCase(Locale.ROOT))) {
                    matchedTerms++;
                }
            }
            String diagnostic = item.path("id").asText() + ": " + hits.stream()
                    .map(KnowledgeFragment::source).toList();
            if (matchedTerms > 0) {
                recalled++;
            } else {
                recallMisses.add(diagnostic);
            }
            int citationThreshold = Math.max(1, (int) Math.ceil(expectedTermCount * 0.4D));
            if (matchedTerms >= citationThreshold) {
                citationCorrect++;
            } else {
                citationMisses.add(diagnostic + " matched=" + matchedTerms + "/" + expectedTermCount);
            }
        }

        double recall = supportedCases.isEmpty() ? 0D : (double) recalled / supportedCases.size();
        double citationAccuracy = supportedCases.isEmpty()
                ? 0D
                : (double) citationCorrect / supportedCases.size();
        assertThat(repository.isReady()).isTrue();
        assertThat(recall).as("recallMisses=%s", recallMisses).isGreaterThanOrEqualTo(0.88D);
        assertThat(citationAccuracy).as("citationMisses=%s", citationMisses).isGreaterThanOrEqualTo(0.88D);
    }

    private Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("ai_engine"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("ai_engine"))) {
            return parent;
        }
        throw new IllegalStateException("Could not locate ai_engine from " + current);
    }
}
