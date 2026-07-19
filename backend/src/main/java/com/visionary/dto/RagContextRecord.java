package com.visionary.dto;

/**
 * Input snapshot for grounding proxy evaluation — counts only, no opaque embeddings.
 */
public record RagContextRecord(
        int retrievedDocs,
        int citedBlocks,
        int responseLength
) {
}
