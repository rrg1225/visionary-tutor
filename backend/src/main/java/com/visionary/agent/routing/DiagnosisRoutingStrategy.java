package com.visionary.agent.routing;

import com.visionary.agent.AgentTaskType;
import com.visionary.dto.AgentInvokeRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 知识诊断路由策略。
 */
@Component
@Order(3)
public class DiagnosisRoutingStrategy implements RoutingStrategy {

    private static final Set<String> KEYWORDS = Set.of(
            "why", "derive", "prove", "diagnos", "原因", "推导", "证明", "诊断", "薄弱"
    );

    @Override
    public boolean matches(AgentInvokeRequest request) {
        String merged = mergePayload(request);
        return KEYWORDS.stream().anyMatch(merged::contains);
    }

    @Override
    public AgentTaskType getTaskType() {
        return AgentTaskType.KNOWLEDGE_DIAGNOSIS;
    }

    private String mergePayload(AgentInvokeRequest request) {
        String p = request.payloadText() != null ? request.payloadText().toLowerCase() : "";
        String t = request.sensoryTags() != null ? request.sensoryTags().toLowerCase() : "";
        return p + " " + t;
    }
}
