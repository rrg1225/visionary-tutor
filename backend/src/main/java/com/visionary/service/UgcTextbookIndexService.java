package com.visionary.service;

import com.visionary.entity.SharedTextbook;
import com.visionary.rag.VectorDbService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UgcTextbookIndexService {

    private static final int CHUNK_SIZE = 1000;
    private static final int CHUNK_OVERLAP = 120;
    private static final Pattern HEADING_SPLIT = Pattern.compile("(?=\\n#{1,3} )");

    private final VectorDbService vectorDbService;

    public void indexTextbook(SharedTextbook book) {
        if (!isAvailable() || book == null || book.getId() == null) {
            return;
        }
        if (!"approved".equals(book.getReviewStatus()) || !"public".equals(book.getVisibility())) {
            return;
        }
        deleteTextbookVectors(book.getId());
        List<String> chunks = chunkMarkdown(book.getContentMarkdown());
        for (int i = 0; i < chunks.size(); i++) {
            upsertChunk(book, chunks.get(i), i);
        }
        log.info("UGC textbook indexed: id={}, chunks={}", book.getId(), chunks.size());
    }

    public void deleteTextbookVectors(Long textbookId) {
        if (!isAvailable() || textbookId == null) {
            return;
        }
        try {
            vectorDbService.deleteBySourcePrefix("ugc-textbook:" + textbookId);
        } catch (Exception e) {
            log.warn("UGC vector delete skipped for textbook {}: {}", textbookId, e.getMessage());
        }
    }

    public boolean isAvailable() {
        return vectorDbService != null && vectorDbService.isAvailable();
    }

    private void upsertChunk(SharedTextbook book, String chunk, int index) {
        String text = """
                Title: %s
                Subject: %s
                Description: %s
                Content:
                %s
                """.formatted(
                blankToDash(book.getTitle()),
                blankToDash(book.getSubjectTag()),
                blankToDash(book.getDescription()),
                chunk
        ).trim();
        try {
            Metadata metadata = new Metadata()
                    .put("source", "ugc-textbook:" + book.getId())
                    .put("category", "ugc_textbook/" + blankToDash(book.getSubjectTag()))
                    .put("chunk_type", "ugc_textbook")
                    .put("layer", "ugc_layer")
                    .put("chroma_layer", "application_layer")
                    .put("textbook_id", book.getId().toString())
                    .put("owner_user_id", String.valueOf(book.getOwnerUserId()))
                    .put("title", blankToDash(book.getTitle()))
                    .put("subject_tag", blankToDash(book.getSubjectTag()))
                    .put("source_type", blankToDash(book.getSourceType()))
                    .put("source_title", blankToDash(book.getSourceTitle()))
                    .put("source_url", blankToDash(book.getSourceUrl()))
                    .put("license_name", blankToDash(book.getLicenseName()))
                    .put("rights_confirmed", String.valueOf(Boolean.TRUE.equals(book.getRightsConfirmed())))
                    .put("visibility", blankToDash(book.getVisibility()))
                    .put("review_status", "approved")
                    .put("chunk_index", String.valueOf(index))
                    .put("vector_id", "ugc-textbook-" + book.getId() + "-chunk-" + index);
            vectorDbService.upsert(Document.from(text, metadata));
        } catch (Exception e) {
            log.warn("UGC chunk index failed textbook={} chunk={}: {}", book.getId(), index, e.getMessage());
        }
    }

    private List<String> chunkMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String[] sections = HEADING_SPLIT.split(markdown.trim());
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            if (current.length() + section.length() > CHUNK_SIZE && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                String tail = tailOverlap(current.toString());
                current = new StringBuilder(tail);
            }
            current.append(section).append('\n');
            while (current.length() > CHUNK_SIZE) {
                chunks.add(current.substring(0, CHUNK_SIZE).trim());
                current.delete(0, CHUNK_SIZE - CHUNK_OVERLAP);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    private String tailOverlap(String text) {
        if (text.length() <= CHUNK_OVERLAP) {
            return text;
        }
        return text.substring(text.length() - CHUNK_OVERLAP);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
