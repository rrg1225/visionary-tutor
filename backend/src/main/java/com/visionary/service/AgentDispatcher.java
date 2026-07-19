package com.visionary.service;

import com.visionary.agent.AgentTaskType;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.dto.ResourceGenerationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentDispatcher {

    private static final List<String> RESOURCE_AGENTS = List.of(
            "PlannerAgent", "DocAgent", "QuizAgent", "MindMapAgent", "ReadingAgent",
            "PathAgent", "CodingAgent", "VisualizationAgent"
    );

    private final AgentWorkerPool workerPool;

    public boolean isAvailable() {
        return workerPool.isAvailable();
    }

    public AgentResult dispatchResourceGeneration(
            String runId,
            Long sessionId,
            String topic,
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    ) {
        return dispatchResourceGeneration(runId, sessionId, topic, request, listener, RESOURCE_AGENTS);
    }

    /**
     * 混合架构支持：根据动态规划的选中 Agents 进行调度
     */
    public AgentResult dispatchResourceGeneration(
            String runId,
            Long sessionId,
            String topic,
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            List<String> selectedAgents
    ) {
        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.setRunId(runId);
        blackboard.setCurrentTopic(topic);
        if (request.learnerProfileSnapshot() != null) {
            blackboard.updateProfileSnapshot(request.learnerProfileSnapshot());
        }
        if (request.weakPointsSnapshot() != null) {
            blackboard.put("weakPointsSnapshot", request.weakPointsSnapshot());
        }

        Map<String, Object> input = new HashMap<>();
        input.put("learningSessionId", sessionId);
        input.put("topic", topic);
        input.put("learnerProfileSnapshot", request.learnerProfileSnapshot());
        input.put("weakPointsSnapshot", request.weakPointsSnapshot());
        input.put("emotionSnapshot", request.emotionSnapshot());
        if (request.resourceTypes() != null && !request.resourceTypes().isEmpty()) {
            input.put("resourceTypes", request.resourceTypes());
        }

        Map<String, Object> metadata = new HashMap<>();
        if (listener != null) {
            metadata.put("progressListener", listener);
        }

        // 使用动态选择的 Agents（混合架构）或默认全量 Agents
        List<String> agentsToDispatch = (selectedAgents != null && !selectedAgents.isEmpty())
                ? selectedAgents
                : RESOURCE_AGENTS;

        AgentTask task = new AgentTask(runId, AgentTaskType.RESOURCE_GENERATION.name(), input, agentsToDispatch);
        return workerPool.execute(task, blackboard, metadata);
    }
}
