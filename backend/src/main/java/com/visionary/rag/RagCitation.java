package com.visionary.rag;

/**
 * Traceable citation item used by generated resource cards and citation checks.
 */
public record RagCitation(
        String citationId,
        String source,
        String layer,
        String category,
        double score,
        String excerpt,
        String chunkId,
        String sourcePath,
        Integer chunkIndex,
        Integer chunkStart,
        Integer chunkEnd
) {
    public RagCitation(
            String citationId,
            String source,
            String layer,
            String category,
            double score,
            String excerpt
    ) {
        this(citationId, source, layer, category, score, excerpt, "", "", null, null, null);
    }
}
