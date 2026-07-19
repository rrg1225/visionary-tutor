package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.core.Agent;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.agent.core.MessageBus;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.core.Tool;
import com.visionary.agent.impl.InMemoryMessageBus;
import com.visionary.agent.tool.ArtifactPersistTool;
import com.visionary.agent.tool.ProfileMergeTool;
import com.visionary.agent.tool.RagRetrieveTool;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.rag.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentWorkerPool {

    @Qualifier("activeSupervisorAgent")
    private final Agent supervisorAgent;
    private final MessageBus messageBus;
    private final RagRetrievalService ragRetrievalService;
    private final GeneratedArtifactRepository artifactRepository;
    private final ProfileMergeTool profileMergeTool;
    private final ObjectMapper objectMapper;

    public boolean isAvailable() {
        return supervisorAgent != null;
    }

    public AgentResult execute(AgentTask task, SharedBlackboard blackboard, Map<String, Object> metadata) {
        MessageBus bus = messageBus != null ? messageBus : new InMemoryMessageBus();
        AgentContext context = new AgentContext(blackboard, buildToolRegistry(), bus, task.taskId(), metadata);
        return supervisorAgent.execute(task, context);
    }

    private Map<String, Tool> buildToolRegistry() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("RAGRetrieveTool", new RagRetrieveTool(ragRetrievalService, objectMapper));
        tools.put("ArtifactPersistTool", new ArtifactPersistTool(artifactRepository, objectMapper));
        tools.put("ProfileMergeTool", profileMergeTool);
        return tools;
    }
}
