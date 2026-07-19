package com.visionary.rag;

/**
 * 向量知识检索失败时抛出的受检异常（或运行时异常）。
 * 用于明确区分“检索失败”与“正常空结果”。
 */
public class KnowledgeRetrievalException extends RuntimeException {

    private final String query;
    private final String layer;

    public KnowledgeRetrievalException(String message, String query, String layer, Throwable cause) {
        super(message, cause);
        this.query = query;
        this.layer = layer;
    }

    public String getQuery() {
        return query;
    }

    public String getLayer() {
        return layer;
    }
}
