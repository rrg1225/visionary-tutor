package com.visionary.agent.routing;

import com.visionary.agent.AgentTaskType;
import com.visionary.dto.AgentInvokeRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 默认资源生成路由策略（兜底）。
 */
@Component
@Order(100)
public class DefaultResourceRoutingStrategy implements RoutingStrategy {

    @Override
    public boolean matches(AgentInvokeRequest request) {
        // 兜底策略总是匹配
        return true;
    }

    @Override
    public AgentTaskType getTaskType() {
        return AgentTaskType.RESOURCE_GENERATION;
    }
}
