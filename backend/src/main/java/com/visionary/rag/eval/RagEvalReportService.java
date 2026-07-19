package com.visionary.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RagEvalReportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public LatestRagEvalReport latest() {
        Optional<Path> jsonPath = findLatestJson();
        if (jsonPath.isEmpty()) {
            return LatestRagEvalReport.empty();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(jsonPath.get().toFile(), MAP_TYPE);
            Map<String, Object> summary = asMap(payload.get("summary"));
            List<Map<String, Object>> rows = asRows(payload.get("rows"));
            Optional<Path> markdownPath = matchingMarkdownPath(jsonPath.get());
            String markdown = markdownPath.map(this::readString).orElse("");
            return new LatestRagEvalReport(
                    true,
                    jsonPath.get().toAbsolutePath().normalize().toString(),
                    markdownPath.map(path -> path.toAbsolutePath().normalize().toString()).orElse(""),
                    summary,
                    rows,
                    markdown,
                    Instant.now().toString()
            );
        } catch (Exception ex) {
            return new LatestRagEvalReport(
                    false,
                    jsonPath.get().toAbsolutePath().normalize().toString(),
                    "",
                    Map.of("error", ex.getMessage()),
                    List.of(),
                    "",
                    Instant.now().toString()
            );
        }
    }

    private Optional<Path> findLatestJson() {
        for (Path dir : reportDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Path latest = dir.resolve("rag_eval_latest.json");
            if (Files.isRegularFile(latest)) {
                return Optional.of(latest);
            }
            try (var stream = Files.list(dir)) {
                Optional<Path> newest = stream
                        .filter(path -> path.getFileName().toString().startsWith("rag_eval_"))
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .max(Comparator.comparingLong(this::lastModified));
                if (newest.isPresent()) {
                    return newest;
                }
            } catch (IOException ignored) {
                // Try the next conventional directory.
            }
        }
        return Optional.empty();
    }

    private List<Path> reportDirs() {
        return List.of(
                Path.of("03_测试证据", "reports"),
                Path.of("..", "03_测试证据", "reports"),
                Path.of("reports"),
                Path.of("..", "reports")
        );
    }

    private Optional<Path> matchingMarkdownPath(Path jsonPath) {
        Path latest = jsonPath.getParent().resolve("rag_eval_latest.md");
        if (Files.isRegularFile(latest)) {
            return Optional.of(latest);
        }
        String name = jsonPath.getFileName().toString().replace(".json", ".md");
        Path sibling = jsonPath.getParent().resolve(name);
        return Files.isRegularFile(sibling) ? Optional.of(sibling) : Optional.empty();
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object value) {
        if (value instanceof List<?> rows) {
            return (List<Map<String, Object>>) rows;
        }
        return List.of();
    }

    public record LatestRagEvalReport(
            boolean available,
            String jsonPath,
            String markdownPath,
            Map<String, Object> summary,
            List<Map<String, Object>> rows,
            String markdown,
            String loadedAt
    ) {
        public static LatestRagEvalReport empty() {
            return new LatestRagEvalReport(
                    false,
                    "",
                    "",
                    Map.of("message", "No RAG eval report found. Run python ai_engine/rag_eval_report.py first."),
                    List.of(),
                    "",
                    Instant.now().toString()
            );
        }
    }
}
