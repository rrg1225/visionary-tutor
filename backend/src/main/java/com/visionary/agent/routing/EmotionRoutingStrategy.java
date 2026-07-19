package com.visionary.agent.routing;

import com.visionary.agent.AgentTaskType;
import com.visionary.dto.AgentInvokeRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 情绪/感官路由策略。
 */
@Component
@Order(1)
public class EmotionRoutingStrategy implements RoutingStrategy {

    private static final Set<String> KEYWORDS = Set.of(
            "voice", "face", "emotion", "表情", "语音", "情绪", "感官"
    );

    @Override
    public boolean matches(AgentInvokeRequest request) {
        String merged = mergePayload(request);
        return KEYWORDS.stream().anyMatch(merged::contains);
    }

    @Override
    public AgentTaskType getTaskType() {
        return AgentTaskType.EMOTION_PROFILE;
    }

    private String mergePayload(AgentInvokeRequest request) {
        String p = request.payloadText() != null ? request.payloadText().toLowerCase() : "";
        String t = request.sensoryTags() != null ? request.sensoryTags().toLowerCase() : "";
        return p + " " + t;
    }
}
