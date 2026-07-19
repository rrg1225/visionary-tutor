package com.visionary.agent.routing;

import com.visionary.agent.AgentTaskType;
import com.visionary.dto.AgentInvokeRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 视觉评估路由策略。
 */
@Component
@Order(2)
public class VisionRoutingStrategy implements RoutingStrategy {

    private static final Set<String> KEYWORDS = Set.of(
            "image", "screenshot", "handwriting", "upload", "draft", "图片", "截图", "手写", "作业"
    );

    @Override
    public boolean matches(AgentInvokeRequest request) {
        String merged = mergePayload(request);
        return KEYWORDS.stream().anyMatch(merged::contains);
    }

    @Override
    public AgentTaskType getTaskType() {
        return AgentTaskType.VISUAL_ASSESSMENT;
    }

    private String mergePayload(AgentInvokeRequest request) {
        String p = request.payloadText() != null ? request.payloadText().toLowerCase() : "";
        String t = request.sensoryTags() != null ? request.sensoryTags().toLowerCase() : "";
        return p + " " + t;
    }
}
