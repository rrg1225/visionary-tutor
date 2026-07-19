package com.visionary.rag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * L3 降级：纯本地词项相似度（TF-IDF 余弦），不依赖任何 Embedding API。
 */
@Component
public final class LexicalSimilarityScorer {

    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9_+\\-*/=]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    /**
     * @param weakPoints      学习者薄弱点描述（可含 JSON / 自然语言）
     * @param artifactMarkdown 资源 Markdown 正文
     * @return 归一化相似度 [0.0, 1.0]
     */
    public double score(String weakPoints, String artifactMarkdown) {
        Map<String, Double> queryTf = termFrequency(weakPoints);
        Map<String, Double> docTf = termFrequency(artifactMarkdown);
        if (queryTf.isEmpty() || docTf.isEmpty()) {
            return 0.0D;
        }

        Set<String> vocabulary = new HashSet<>();
        vocabulary.addAll(queryTf.keySet());
        vocabulary.addAll(docTf.keySet());

        Map<String, Double> idf = inverseDocumentFrequency(queryTf.keySet(), docTf.keySet());
        double dot = 0.0D;
        double normQuery = 0.0D;
        double normDoc = 0.0D;

        for (String term : vocabulary) {
            double queryWeight = queryTf.getOrDefault(term, 0.0D) * idf.getOrDefault(term, 0.0D);
            double docWeight = docTf.getOrDefault(term, 0.0D) * idf.getOrDefault(term, 0.0D);
            dot += queryWeight * docWeight;
            normQuery += queryWeight * queryWeight;
            normDoc += docWeight * docWeight;
        }

        if (normQuery <= 0.0D || normDoc <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, dot / (Math.sqrt(normQuery) * Math.sqrt(normDoc))));
    }

    private Map<String, Double> termFrequency(String text) {
        Map<String, Double> tf = new HashMap<>();
        if (text == null || text.isBlank()) {
            return tf;
        }
        for (String term : tokenize(text)) {
            tf.merge(term, 1.0D, Double::sum);
        }
        int total = tf.values().stream().mapToInt(v -> (int) Math.round(v)).sum();
        if (total <= 0) {
            return tf;
        }
        tf.replaceAll((term, count) -> count / total);
        return tf;
    }

    private Map<String, Double> inverseDocumentFrequency(Set<String> queryTerms, Set<String> docTerms) {
        Map<String, Double> idf = new HashMap<>();
        Set<String> union = new HashSet<>();
        union.addAll(queryTerms);
        union.addAll(docTerms);
        for (String term : union) {
            int docFreq = (queryTerms.contains(term) ? 1 : 0) + (docTerms.contains(term) ? 1 : 0);
            idf.put(term, Math.log(1.0D + (2.0D / docFreq)));
        }
        return idf;
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT);

        Matcher latin = LATIN_TERM.matcher(normalized);
        while (latin.find()) {
            terms.add(latin.group());
        }

        Matcher han = HAN_RUN.matcher(text);
        while (han.find()) {
            String run = han.group();
            terms.add(run);
            if (run.length() >= 2) {
                for (int i = 0; i < run.length() - 1; i++) {
                    terms.add(run.substring(i, i + 2));
                }
            }
        }
        return terms;
    }
}
