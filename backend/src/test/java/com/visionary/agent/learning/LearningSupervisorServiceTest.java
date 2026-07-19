package com.visionary.agent.learning;

import com.visionary.dto.ChatMessageDto;
import com.visionary.dto.StreamChatRequest;
import com.visionary.rag.KnowledgeLayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LearningSupervisorServiceTest {

    private final LearningSupervisorService service = new LearningSupervisorService();

    @Test
    void routesCodeQuestionToCodeToolsAndLayers() {
        LearningAgentPlan plan = service.plan(request("PyTorch shape 报错怎么定位？"), "PyTorch shape 报错怎么定位？");

        assertEquals(LearningAgentPlan.Intent.CODE_TUTORING, plan.intent());
        assertTrue(plan.requiresGrounding());
        assertTrue(plan.ragLayers().contains(KnowledgeLayer.CODE));
        assertTrue(plan.tools().contains("knowledge.retrieve"));
    }

    @Test
    void platformGuidanceAvoidsUnnecessaryRag() {
        LearningAgentPlan plan = service.plan(request("这个平台怎么用？"), "这个平台怎么用？");

        assertEquals(LearningAgentPlan.Intent.PLATFORM_GUIDANCE, plan.intent());
        assertFalse(plan.requiresGrounding());
        assertTrue(plan.ragLayers().isEmpty());
        assertFalse(plan.tools().contains("knowledge.retrieve"));
    }

    @Test
    void assessmentPlanProtectsUnsubmittedAnswersAndExplainsErrors() {
        LearningAgentPlan plan = service.plan(request("这道题为什么错？"), "这道题为什么错？");

        assertEquals(LearningAgentPlan.Intent.ASSESSMENT_SUPPORT, plan.intent());
        assertTrue(plan.responseStrategy().contains("未提交时优先提示或分步"));
        assertTrue(plan.responseStrategy().contains("提交后再解释"));
        assertTrue(plan.ragLayers().contains(KnowledgeLayer.ASSESSMENT));
    }

    private StreamChatRequest request(String query) {
        return new StreamChatRequest(
                null,
                List.of(new ChatMessageDto("user", query)),
                query,
                true,
                false,
                query,
                "TUTORING",
                null,
                "{}",
                "",
                "{}",
                "AUTO"
        );
    }
}
