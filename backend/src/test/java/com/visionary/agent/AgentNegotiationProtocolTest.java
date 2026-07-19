package com.visionary.agent;

import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentMessageType;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.MessageBus;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.impl.InMemoryMessageBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentNegotiationProtocolTest {

    @Test
    void publishPeerOutlines_makesFinalPhaseConsumePeerContext() {
        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.put("DocAgent_outline", "覆盖 CNN 基础概念与公式推导");
        blackboard.put("QuizAgent_outline", "分层练习题：基础+应用");

        AgentCollaborationSupport.publishPeerOutlines(blackboard, List.of("DocAgent", "QuizAgent"));

        String block = AgentCollaborationSupport.negotiationContextBlock(blackboard, "MindMapAgent");
        assertTrue(block.contains("DocAgent"));
        assertTrue(block.contains("QuizAgent"));
        assertTrue(block.contains("OUTLINE"));
    }

    @Test
    void publishOutlineProposal_recordsDebateAndMessageBus() {
        MessageBus bus = new InMemoryMessageBus();
        SharedBlackboard blackboard = new SharedBlackboard();
        AgentContext context = new AgentContext(blackboard, Map.of(), bus, "run-1", Map.of());

        AgentNegotiationProtocol.publishOutlineProposal(context, "DocAgent", "将讲解 padding 与 stride");

        assertEquals("将讲解 padding 与 stride", blackboard.get("DocAgent_outline"));
        assertFalse(blackboard.getDebateLog().isEmpty());

        List<com.visionary.agent.core.AgentMessage> toSupervisor =
                bus.poll(AgentNegotiationProtocol.COORDINATOR_ROLE, 5);
        assertFalse(toSupervisor.isEmpty());
        assertEquals(AgentMessageType.OUTLINE_PROPOSAL, toSupervisor.get(0).type());
        assertEquals("DocAgent", toSupervisor.get(0).fromRole());
    }

    @Test
    void publishPeerSummaries_skipsOutlineResults() {
        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.put("DocAgent_result", new AgentResult(
                true,
                "完整讲义正文",
                List.of(),
                Map.of("artifactType", "HANDOUT"),
                List.of()
        ));
        blackboard.put("QuizAgent_outline_result", new AgentResult(
                true,
                "仅 outline",
                List.of(),
                Map.of("negotiationPhase", AgentNegotiationProtocol.PHASE_OUTLINE),
                List.of()
        ));

        AgentCollaborationSupport.publishPeerSummaries(blackboard, List.of("DocAgent", "QuizAgent"));

        @SuppressWarnings("unchecked")
        Map<String, String> summaries = (Map<String, String>) blackboard.get(AgentCollaborationSupport.PEER_SUMMARIES_KEY);
        assertNotNull(summaries);
        assertTrue(summaries.containsKey("DocAgent"));
        assertFalse(summaries.containsKey("QuizAgent"));
    }
}
