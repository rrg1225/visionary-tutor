package com.visionary.rag.repository;

import com.visionary.rag.KnowledgeRetrievalException;
import com.visionary.rag.VectorDbService;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ChromaKnowledgeSearchRepository implements KnowledgeSearchRepository {

    private final VectorDbService vectorDbService;
    private final ExecutorService searchExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "chroma-knowledge-search");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public String backendName() {
        return KnowledgeSearchOutcome.MODE_CHROMA;
    }

    @Override
    public boolean isReady() {
        return vectorDbService.isAvailable();
    }

    @Override
    public List<KnowledgeFragment> search(String query, int topK, Set<String> allowedLayers) {
        return searchWithTimeout(query, topK, allowedLayers, TimeUnit.SECONDS.toMillis(30));
    }

    public List<KnowledgeFragment> searchWithTimeout(
            String query,
            int topK,
            Set<String> allowedLayers,
            long timeoutMs
    ) {
        if (!isReady() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Callable<List<KnowledgeFragment>> task = () -> vectorDbService.search(query, topK, allowedLayers);
        Future<List<KnowledgeFragment>> future = searchExecutor.submit(task);
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new KnowledgeRetrievalException(
                    "Chroma search timed out after " + timeoutMs + "ms",
                    query,
                    "visionary_global_knowledge",
                    e
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof KnowledgeRetrievalException retrievalException) {
                throw retrievalException;
            }
            throw new KnowledgeRetrievalException(
                    "Chroma search failed",
                    query,
                    "visionary_global_knowledge",
                    cause
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeRetrievalException(
                    "Chroma search interrupted",
                    query,
                    "visionary_global_knowledge",
                    e
            );
        }
    }
}
