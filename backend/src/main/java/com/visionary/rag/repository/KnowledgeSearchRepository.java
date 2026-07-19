package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;

import java.util.List;
import java.util.Set;

/**
 * Repository abstraction for knowledge retrieval backends (Chroma vector / in-memory BM25).
 */
public interface KnowledgeSearchRepository {

    String backendName();

    boolean isReady();

    List<KnowledgeFragment> search(String query, int topK, Set<String> allowedLayers);
}
