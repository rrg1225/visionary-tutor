package com.visionary.rag;

import com.visionary.rag.repository.KnowledgeSearchOutcome;

import java.util.List;

/**
 * Layered RAG retrieval result: context blocks plus stable citation metadata.
 */
public record RagRetrievalResult(
        String applicationContext,
        String algorithmContext,
        String mathContext,
        String groundedContextBlock,
        List<RagCitation> citations,
        boolean hasGroundedEvidence,
        String retrievalMode,
        boolean highAvailabilityFallback
) {

    public RagRetrievalResult {
        if (retrievalMode == null || retrievalMode.isBlank()) {
            retrievalMode = KnowledgeSearchOutcome.MODE_NONE;
        }
    }

    public static RagRetrievalResult empty() {
        return new RagRetrievalResult("", "", "", "", List.of(), false, KnowledgeSearchOutcome.MODE_NONE, false);
    }

    public boolean isGrounded() {
        return hasGroundedEvidence;
    }

    public String toCitationInstructionBlock() {
        if (!hasGroundedEvidence) {
            return """
                    [可选知识库] 本次没有召回足够相似的补充材料。
                    [生成策略] 请基于模型已有知识和用户主题正常、完整地生成，不要因此降级、缩减或拒绝回答。
                    [引用约束] 不得虚构知识库来源或 citationId；citations 必须为空数组 []。
                    """;
        }
        String modeHint = highAvailabilityFallback
                ? "[检索模式] 知识库已切换至高可用 BM25 本地检索。\n"
                : KnowledgeSearchOutcome.MODE_HYBRID.equals(retrievalMode)
                ? "[检索模式] 已启用 Chroma 向量 + BM25 关键词混合检索（RRF 融合）。\n"
                : "";
        StringBuilder sb = new StringBuilder();
        sb.append(modeHint);
        sb.append("[可选知识库] 以下片段是模型已有知识之外的补充材料，不限制模型正常回答。\n");
        sb.append("[引用规则] 只有实际采用下列材料时才标注对应 citationId；未采用可不引用，不得虚构来源。\n\n");
        for (RagCitation c : citations) {
            sb.append("- ").append(c.citationId())
                    .append(" | layer=").append(c.layer())
                    .append(" | source=").append(c.source())
                    .append(" | chunkId=").append(c.chunkId())
                    .append(" | sourcePath=").append(c.sourcePath())
                    .append(" | chunkIndex=").append(c.chunkIndex() == null ? "-" : c.chunkIndex())
                    .append(" | score=").append(String.format("%.3f", c.score()))
                    .append("\n");
        }
        return sb.toString();
    }
}
