package com.visionary.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.visionary.knowledge.SystemKnowledgeDtos.ContentSummary;
import static com.visionary.knowledge.SystemKnowledgeDtos.ContentView;

@Service
@RequiredArgsConstructor
public class SystemKnowledgeCatalogService {

    private static final String CATALOG_RESOURCE = "knowledge/system-knowledge-v1.json";
    private static final int EXPECTED_ITEMS = 12;
    private static final int MIN_BOOK_CHARS = 5_000;
    private static final int MIN_SHORT_FORM_CHARS = 1_200;

    private final ObjectMapper objectMapper;
    private final Map<String, String> contentCache = new HashMap<>();
    private SystemKnowledgeCatalog catalog;

    @PostConstruct
    public void initialize() {
        this.catalog = readCatalog();
        validateCatalog(catalog);
    }

    public List<ContentSummary> list() {
        return catalog.items().stream().map(this::toSummary).toList();
    }

    public ContentView get(String slug) {
        SystemKnowledgeCatalog.Item item = requireItem(slug);
        return new ContentView(
                toSummary(item),
                List.copyOf(item.prerequisites()),
                List.copyOf(item.sections()),
                content(item)
        );
    }

    public SystemKnowledgeCatalog.Item requireItem(String slug) {
        String normalized = normalize(slug);
        return catalog.items().stream()
                .filter(item -> normalize(item.slug()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "系统学习内容不存在"));
    }

    public String content(SystemKnowledgeCatalog.Item item) {
        return contentCache.computeIfAbsent(item.slug(), ignored -> readText(item.contentResource()));
    }

    public void validateCatalog(SystemKnowledgeCatalog candidate) {
        require(candidate != null, "catalog is required");
        require(nonBlank(candidate.version()), "catalog version is required");
        require("TEAM_REVIEW_REQUIRED".equals(candidate.reviewStatus()), "catalog must disclose team review status");
        require(candidate.items() != null && candidate.items().size() == EXPECTED_ITEMS,
                "catalog must contain exactly 12 items");

        Set<String> slugs = new HashSet<>();
        Map<ContentKind, Integer> kindCounts = new EnumMap<>(ContentKind.class);
        for (SystemKnowledgeCatalog.Item item : candidate.items()) {
            validateItem(item, slugs);
            ContentKind kind = ContentKind.valueOf(item.kind().toUpperCase(Locale.ROOT));
            kindCounts.merge(kind, 1, Integer::sum);
        }
        require(kindCounts.getOrDefault(ContentKind.BOOK, 0) >= 4, "catalog needs at least four books");
        require(kindCounts.getOrDefault(ContentKind.ARTICLE, 0) >= 3, "catalog needs at least three articles");
        require(kindCounts.getOrDefault(ContentKind.PAPER, 0) >= 3, "catalog needs at least three paper guides");
        require(kindCounts.getOrDefault(ContentKind.REVIEW, 0) >= 2, "catalog needs at least two review guides");
    }

    private void validateItem(SystemKnowledgeCatalog.Item item, Set<String> slugs) {
        require(item != null, "content item is required");
        require(nonBlank(item.slug()) && slugs.add(normalize(item.slug())), "content slug must be unique");
        ContentKind kind;
        try {
            kind = ContentKind.valueOf(item.kind().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new IllegalStateException("unsupported content kind: " + item.kind(), exception);
        }
        require(nonBlank(item.title()) && nonBlank(item.description()), "content title and description are required");
        require(nonBlank(item.authorLabel()), "content author label is required");
        require(item.estimatedMinutes() > 0, "estimated minutes must be positive");
        require(item.learningObjectives() != null && item.learningObjectives().size() >= 3,
                "at least three learning objectives are required");
        require(item.prerequisites() != null && !item.prerequisites().isEmpty(), "prerequisites are required");
        require(item.sections() != null && !item.sections().isEmpty(), "sections are required");
        if (kind == ContentKind.BOOK) {
            require(item.sections().size() >= 5 && item.sections().size() <= 8,
                    "books must contain five to eight chapters");
        }
        Set<String> sectionIds = new HashSet<>();
        Set<Integer> sectionOrders = new HashSet<>();
        for (SystemKnowledgeCatalog.Section section : item.sections()) {
            require(nonBlank(section.id()) && sectionIds.add(section.id()), "section id must be unique");
            require(section.order() > 0 && sectionOrders.add(section.order()), "section order must be unique");
            require(nonBlank(section.title()) && nonBlank(section.summary()), "section title and summary are required");
            require(section.reflectionQuestions() != null && section.reflectionQuestions().size() >= 3,
                    "each section needs at least three reflection questions");
            require(section.commonErrors() != null && !section.commonErrors().isEmpty(),
                    "each section needs common errors");
        }
        require(item.sources() != null && !item.sources().isEmpty(), "sources are required");
        for (SystemKnowledgeCatalog.Source source : item.sources()) {
            require(nonBlank(source.title()) && isHttpUrl(source.url()), "source title and valid URL are required");
            require(nonBlank(source.sourceType()), "source type is required");
        }
        String markdown = content(item);
        int minimum = kind == ContentKind.BOOK ? MIN_BOOK_CHARS : MIN_SHORT_FORM_CHARS;
        require(markdown.replaceAll("\\s+", "").length() >= minimum,
                item.slug() + " content is shorter than " + minimum + " characters");
    }

    private ContentSummary toSummary(SystemKnowledgeCatalog.Item item) {
        return new ContentSummary(
                item.slug(),
                item.kind(),
                item.title(),
                item.subtitle(),
                item.description(),
                item.subject(),
                item.difficulty(),
                item.audience(),
                item.estimatedMinutes(),
                item.authorLabel(),
                item.publicationYear(),
                item.venue(),
                catalog.version(),
                catalog.reviewStatus(),
                item.licenseName(),
                item.sections().size(),
                content(item).replaceAll("\\s+", "").length(),
                List.copyOf(item.learningObjectives()),
                List.copyOf(item.sources())
        );
    }

    private SystemKnowledgeCatalog readCatalog() {
        try (var input = new ClassPathResource(CATALOG_RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, SystemKnowledgeCatalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load system knowledge catalog", exception);
        }
    }

    private String readText(String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load system knowledge content: " + resource, exception);
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("http", "https").contains(uri.getScheme()) && nonBlank(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid system knowledge catalog: " + message);
        }
    }

    private enum ContentKind {
        BOOK,
        ARTICLE,
        PAPER,
        REVIEW
    }
}
