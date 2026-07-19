package com.visionary.dto;

import com.visionary.entity.DiagnosticWeakNode;

public record DiagnosticWeakNodeRequest(
        String nodeName,
        DiagnosticWeakNode.KnowledgeLayer knowledgeLayer,
        Integer masteryScore
) {
}
