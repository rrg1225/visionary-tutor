package com.visionary.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CitationValidator {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\bcite-[\\p{IsHan}A-Za-z0-9_.:-]+\\b");
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("[^。！？!?\\n]+(?:[。！？!?]|$)");
    private static final double MIN_GROUNDING_OVERLAP = 0.12D;

    private final RagGroundingScorer groundingScorer;

    public ValidationResult validate(String generatedText, RagRetrievalResult rag) {
        Set<String> used = extractCitationIds(generatedText);
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, RagCitation> citationById = new HashMap<>();
        if (rag != null && rag.citations() != null) {
            rag.citations().forEach(citation -> {
                allowed.add(citation.citationId());
                citationById.put(citation.citationId(), citation);
            });
        }

        Set<String> invalid = new LinkedHashSet<>(used);
        invalid.removeAll(allowed);

        if (!invalid.isEmpty()) {
            return new ValidationResult("INVALID_CITATION", "发现未检索到的引用：" + invalid);
        }
        if (allowed.isEmpty() && !used.isEmpty()) {
            return new ValidationResult("INVALID_CITATION", "无检索证据时不允许输出引用");
        }
        if (allowed.isEmpty()) {
            return new ValidationResult(
                    "NO_EVIDENCE",
                    "本次没有知识库补充材料；内容由主模型直接生成，未使用外部引用"
            );
        }
        if (used.isEmpty() && generatedText != null && generatedText.length() > 160) {
            return new ValidationResult(
                    "RAG_UNUSED",
                    "知识库返回了补充材料，但成品没有引用它；按主模型直接生成内容发布"
            );
        }

        Set<String> weak = findWeakGrounding(generatedText, citationById);
        if (!weak.isEmpty()) {
            return new ValidationResult("WEAK_GROUNDING", "引用 ID 合法，但引用附近文本与原文片段重合度偏低：" + weak);
        }
        return new ValidationResult("GROUNDED", "引用来自本次检索结果，且引用附近文本通过轻量忠实度校验");
    }

    private Set<String> extractCitationIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private Set<String> findWeakGrounding(String text, Map<String, RagCitation> citationById) {
        Set<String> weak = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return weak;
        }
        Matcher sentenceMatcher = SENTENCE_PATTERN.matcher(text);
        while (sentenceMatcher.find()) {
            String sentence = sentenceMatcher.group();
            for (String id : extractCitationIds(sentence)) {
                RagCitation citation = citationById.get(id);
                if (citation == null || citation.excerpt() == null || citation.excerpt().isBlank()) {
                    weak.add(id);
                    continue;
                }
                double overlap = groundingScorer.overlapScore(sentence, citation.excerpt());
                // Enhanced content match: also require key phrases from excerpt to appear near citation
                boolean phraseMatch = hasKeyPhraseMatch(sentence, citation.excerpt());
                if (overlap < MIN_GROUNDING_OVERLAP || !phraseMatch) {
                    weak.add(id);
                }
            }
        }
        return weak;
    }

    private boolean hasKeyPhraseMatch(String sentence, String excerpt) {
        // Simple content match: check if at least 2 significant terms from excerpt appear in sentence
        Set<String> excerptTerms = groundingScorer.terms(excerpt);
        int matchCount = 0;
        for (String term : excerptTerms) {
            if (sentence.toLowerCase().contains(term.toLowerCase())) {
                matchCount++;
            }
            if (matchCount >= 2) return true;
        }
        return matchCount >= 2;
    }

    public record ValidationResult(String status, String message) {
    }
}
