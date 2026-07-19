package com.visionary.agent.routing;

import com.visionary.agent.AgentTaskType;
import com.visionary.dto.AgentInvokeRequest;

/**
 * 路由策略接口。
 * 实现类负责判断请求是否匹配特定任务类型，实现职责分离，避免 God Method。
 */
public interface RoutingStrategy {

    /**
     * 判断请求是否匹配此策略。
     * @param request 路由请求
     * @return true 表示匹配
     */
    boolean matches(AgentInvokeRequest request);

    /**
     * 返回此策略对应的任务类型。
     */
    AgentTaskType getTaskType();
}
