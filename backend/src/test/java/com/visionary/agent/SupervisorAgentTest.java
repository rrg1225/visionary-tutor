package com.visionary.agent;

import com.visionary.agent.core.*;
import com.visionary.agent.worker.DistributedHandoffExecutor;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.config.GovernanceProperties;
import com.visionary.entity.AgentExecutionLog;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.repository.AgentExecutionLogRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.service.GovernanceTraceService;
import com.visionary.governance.CompositeScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SupervisorAgent 单元测试
 * 测试 Legacy 模式下 Blackboard 和 Specialist Agent 驱动
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupervisorAgentTest {

    @Test
    void explicitRequiredRolesLimitSpecialistDispatch() {
        AgentTask scopedTask = new AgentTask(
                "scoped-code", "RESOURCE_GENERATION", Map.of(),
                List.of("PlannerAgent", "CodingAgent", "CriticAgent"));

        assertEquals(List.of("CodingAgent"), SupervisorAgent.selectParallelSpecialists(scopedTask));
    }

    @Mock
    private MessageBus messageBus;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @Mock
    private DistributedHandoffExecutor distributedHandoffExecutor;

    @Mock
    private AgentOrchestrationProperties orchestrationProps;

    @Mock
    private GovernanceProperties governanceProperties;

    @Mock
    private AgentExecutionLogRepository executionLogRepository;

    @Mock
    private GovernanceTraceService governanceTraceService;

    @Mock
    private GeneratedArtifactRepository artifactRepository;

    @Mock
    private CompositeScoreCalculator compositeScoreCalculator;

    @Mock
    private Agent plannerAgent;

    @Mock
    private Agent docAgent;

    @Mock
    private Agent quizAgent;

    @Mock
    private Agent criticAgent;

    @Mock
    private Agent pathAgent;

    @Mock
    private Agent mindMapAgent;

    @Mock
    private Agent readingAgent;

    @Mock
    private Agent codingAgent;

    @Mock
    private Agent visualizationAgent;

    @Mock
    private Agent reviewAgent;

    @InjectMocks
    private SupervisorAgent supervisorAgent;

    private Map<String, Agent> agentRegistry;

    @BeforeEach
    void setUp() {
        agentRegistry = new HashMap<>();
        agentRegistry.put("PlannerAgent", plannerAgent);
        agentRegistry.put("DocAgent", docAgent);
        agentRegistry.put("QuizAgent", quizAgent);
        agentRegistry.put("CriticAgent", criticAgent);
        agentRegistry.put("PathAgent", pathAgent);
        agentRegistry.put("MindMapAgent", mindMapAgent);
        agentRegistry.put("ReadingAgent", readingAgent);
        agentRegistry.put("CodingAgent", codingAgent);
        agentRegistry.put("VisualizationAgent", visualizationAgent);
        agentRegistry.put("ReviewAgent", reviewAgent);

        supervisorAgent = new SupervisorAgent(
                messageBus,
                agentRegistry,
                ragRetrievalService,
                distributedHandoffExecutor,
                orchestrationProps,
                governanceProperties,
                executionLogRepository,
                governanceTraceService,
                artifactRepository,
                compositeScoreCalculator,
                Runnable::run
        );

        when(governanceProperties.getMaxRevisionRounds()).thenReturn(5);
        when(orchestrationProps.getMaxRevisionRounds()).thenReturn(5);
        when(orchestrationProps.getSpecialistTimeoutSeconds()).thenReturn(5);
        when(orchestrationProps.getTotalTimeoutSeconds()).thenReturn(10);
        when(orchestrationProps.isEnableManualReviewFallback()).thenReturn(true);
        when(distributedHandoffExecutor.isActive()).thenReturn(false);
        when(ragRetrievalService.retrieveForTask(any(), anyString())).thenReturn(RagRetrievalResult.empty());

        AgentResult pass = new AgentResult(true, "OK", List.of(), Map.of("verdict", "PASS", "critique", "ok"), List.of());
        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Plan created", List.of(), Map.of("plan", "default plan"), List.of())
        );
        when(docAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Handout", List.of(), Map.of("artifactType", "HANDOUT"), List.of())
        );
        when(quizAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Quiz", List.of(), Map.of("artifactType", "QUIZ"), List.of())
        );
        when(mindMapAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Mind map", List.of(), Map.of("artifactType", "MINDMAP"), List.of())
        );
        when(readingAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Reading", List.of(), Map.of("artifactType", "EXTENDED_READING"), List.of())
        );
        when(codingAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Coding", List.of(), Map.of("artifactType", "CODE_PRACTICE"), List.of())
        );
        when(visualizationAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Visualization", List.of(), Map.of("artifactType", "VISUALIZATION"), List.of())
        );
        when(pathAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Path", List.of(), Map.of("artifactType", "LEARNING_PATH"), List.of())
        );
        when(reviewAgent.execute(any(), any())).thenReturn(pass);
        when(criticAgent.execute(any(), any())).thenReturn(pass);
    }

    private static AgentResult specialistResult(String artifactType, String content) {
        return new AgentResult(
                true,
                content,
                List.of(),
                Map.of("artifactType", artifactType),
                List.of()
        );
    }

    @Test
    void testExecute_InLegacyMode_ShouldPutTaskToBlackboardAndDriveSpecialists() {
        // Given: 准备 Legacy 模式测试数据
        String runId = UUID.randomUUID().toString();
        String topic = "Python 编程基础";

        Map<String, Object> taskInput = new HashMap<>();
        taskInput.put("topic", topic);
        taskInput.put("learnerProfileSnapshot", "{\"goal\": \"学习 Python\"}");

        AgentTask task = new AgentTask(
                "task-001",
                "RESOURCE_GENERATION",
                taskInput,
                List.of("PlannerAgent", "DocAgent", "QuizAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.setCurrentTopic(topic);

        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        // Mock RAG 检索
        when(ragRetrievalService.retrieveForTask(any(), anyString()))
                .thenReturn(RagRetrievalResult.empty());

        // Mock 分布式执行器未激活（Legacy 模式 - 进程内执行）
        when(distributedHandoffExecutor.isActive()).thenReturn(false);

        // Mock PlannerAgent 执行成功
        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "Plan created successfully",
                        List.of(),
                        Map.of("plan", "Python 学习路径规划", "learningStyle", "balanced"),
                        List.of()
                )
        );

        // Mock DocAgent 执行成功
        when(docAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "# Python 基础讲义\n\n这是讲义内容...",
                        List.of(),
                        Map.of("artifactType", "HANDOUT"),
                        List.of()
                )
        );

        // Mock QuizAgent 执行成功
        when(quizAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "## Python 练习题\n\n1. 什么是变量？",
                        List.of(),
                        Map.of("artifactType", "QUIZ"),
                        List.of()
                )
        );

        // Mock CriticAgent - 不需要返修
        when(criticAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "Review passed",
                        List.of(),
                        Map.of("verdict", "PASS", "critique", "内容完整，审查通过"),
                        List.of()
                )
        );

        // Mock PathAgent 执行成功
        when(pathAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "Learning path generated",
                        List.of(),
                        Map.of("artifactType", "LEARNING_PATH"),
                        List.of()
                )
        );

        // When: 执行测试
        AgentResult result = supervisorAgent.execute(task, context);

        // Then: 验证结果
        assertNotNull(result);
        assertTrue(result.success());

        // 验证 MessageBus 发布了消息（用于黑板通信）
        verify(messageBus, atLeast(1)).publish(any(AgentMessage.class));

        // 验证各 Specialist Agent 被调用
        verify(plannerAgent, times(1)).execute(any(), any());

        // 验证 Blackboard 中有 Specialist 任务结果
        assertNotNull(blackboard.get("DocAgent_result"));
    }

    @Test
    void testExecute_WithCriticRevision_ShouldRetrySpecialist() {
        // Given: Critic 要求返修的场景
        String runId = UUID.randomUUID().toString();

        Map<String, Object> taskInput = new HashMap<>();
        taskInput.put("topic", "Java 并发");

        AgentTask task = new AgentTask(
                "task-002",
                "RESOURCE_GENERATION",
                taskInput,
                List.of("PlannerAgent", "DocAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        when(ragRetrievalService.retrieveForTask(any(), anyString()))
                .thenReturn(RagRetrievalResult.empty());
        when(distributedHandoffExecutor.isActive()).thenReturn(false);

        // Planner 成功
        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Plan OK", List.of(), Map.of(), List.of())
        );

        // DocAgent 第一次执行
        when(docAgent.execute(any(), any()))
                .thenReturn(new AgentResult(
                        true,
                        "Initial content",
                        List.of(),
                        Map.of("artifactType", "HANDOUT"),
                        List.of()
                ))
                .thenReturn(new AgentResult(
                        true,
                        "Revised content",
                        List.of(),
                        Map.of("artifactType", "HANDOUT", "revised", true),
                        List.of()
                ));  // 第二次返回修改后的内容

        // Critic 第一次要求返修，第二次通过
        when(criticAgent.execute(any(), any()))
                .thenReturn(new AgentResult(
                        true,
                        "Need revision",
                        List.of(),
                        Map.of("verdict", "REVISE", "critique", "需要补充更多示例"),
                        List.of()
                ))
                .thenReturn(new AgentResult(
                        true,
                        "Passed",
                        List.of(),
                        Map.of("verdict", "PASS"),
                        List.of()
                ));

        when(pathAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Path OK", List.of(), Map.of(), List.of())
        );

        // When: 执行测试
        AgentResult result = supervisorAgent.execute(task, context);

        // Then: 验证 DocAgent 被调用了多次（初始 + 返修）
        verify(docAgent, atLeast(2)).execute(any(), any());
        verify(criticAgent, atLeast(2)).execute(any(), any());
    }

    @Test
    void testExecute_WhenPlannerFails_ShouldReturnFailure() {
        // Given: Planner 失败的场景
        String runId = UUID.randomUUID().toString();

        AgentTask task = new AgentTask(
                "task-003",
                "RESOURCE_GENERATION",
                Map.of("topic", "失败测试"),
                List.of("PlannerAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        when(distributedHandoffExecutor.isActive()).thenReturn(false);

        // Planner 失败
        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        false,
                        "Planning failed: timeout",
                        List.of(),
                        Map.of(),
                        List.of()
                )
        );

        // When/Then: 执行并期望抛出异常
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            supervisorAgent.execute(task, context);
        });

        assertTrue(exception.getMessage().contains("PlannerAgent failed"));
    }

    @Test
    void testExecute_WithDistributedMode_ShouldUseHandoffExecutor() {
        // Given: 分布式模式
        String runId = UUID.randomUUID().toString();

        AgentTask task = new AgentTask(
                "task-004",
                "RESOURCE_GENERATION",
                Map.of("topic", "分布式测试"),
                List.of("PlannerAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        // 启用分布式模式
        when(distributedHandoffExecutor.isActive()).thenReturn(true);
        when(distributedHandoffExecutor.executeHandoff(anyString(), any(), any(), anyString()))
                .thenReturn(new AgentResult(
                        true,
                        "Distributed result",
                        List.of(),
                        Map.of("distributed", true),
                        List.of()
                ));

        when(ragRetrievalService.retrieveForTask(any(), anyString()))
                .thenReturn(RagRetrievalResult.empty());

        when(criticAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Pass", List.of(), Map.of("verdict", "PASS"), List.of())
        );

        when(pathAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Path", List.of(), Map.of(), List.of())
        );

        // When: 执行测试
        AgentResult result = supervisorAgent.execute(task, context);

        // Then: 验证使用了分布式执行器
        verify(distributedHandoffExecutor, atLeastOnce()).executeHandoff(anyString(), any(), any(), anyString());
        assertTrue(result.success());
    }

    @Test
    void testExecute_WithManualReviewRequired_ShouldLogAndReturn() {
        // Given: 达到最大返修次数仍不通过，需要人工审核
        String runId = UUID.randomUUID().toString();

        AgentTask task = new AgentTask(
                "task-005",
                "RESOURCE_GENERATION",
                Map.of("topic", "人工审核测试"),
                List.of("PlannerAgent", "DocAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        when(ragRetrievalService.retrieveForTask(any(), anyString()))
                .thenReturn(RagRetrievalResult.empty());
        when(distributedHandoffExecutor.isActive()).thenReturn(false);

        // Planner 成功
        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Plan OK", List.of(), Map.of(), List.of())
        );

        // DocAgent 总是返回需要返修的内容
        when(docAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "Problematic content",
                        List.of(),
                        Map.of("artifactType", "HANDOUT"),
                        List.of()
                )
        );

        // Critic 总是要求返修
        when(criticAgent.execute(any(), any())).thenReturn(
                new AgentResult(
                        true,
                        "Always need revision",
                        List.of(),
                        Map.of("verdict", "REVISE", "critique", "内容质量不达标"),
                        List.of()
                )
        );

        // 设置较小的最大返修轮次以加速测试
        when(orchestrationProps.getMaxRevisionRounds()).thenReturn(1);

        // When: 执行测试
        AgentResult result = supervisorAgent.execute(task, context);

        // Then: 验证结果标记为需要人工审核
        assertFalse(result.success()); // success = false 因为需要人工干预
        assertEquals("MANUAL_REVIEW_REQUIRED",
                result.metadata().get("workflowState"));

        // 验证记录了日志
        verify(executionLogRepository, atLeastOnce()).save(any(AgentExecutionLog.class));
    }

    @Test
    void testGetRole_ShouldReturnSupervisor() {
        assertEquals("Supervisor", supervisorAgent.getRole());
    }

    @Test
    void testGetSupportedTools_ShouldReturnEmptySet() {
        assertTrue(supervisorAgent.getSupportedTools().isEmpty());
    }

    @Test
    void testRunFullWorkflow_ShouldCompleteAllPhases() {
        // Given: 完整的编排工作流测试
        String runId = UUID.randomUUID().toString();
        String topic = "机器学习入门";

        Map<String, Object> input = new HashMap<>();
        input.put("topic", topic);
        input.put("learnerProfileSnapshot", "{\"style\": \"visual\"}");

        AgentTask task = new AgentTask(
                runId,
                "RESOURCE_GENERATION",
                input,
                List.of("PlannerAgent", "DocAgent", "QuizAgent", "MindMapAgent",
                        "ReadingAgent", "CodingAgent", "VisualizationAgent")
        );

        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.setCurrentTopic(topic);

        AgentContext context = new AgentContext(
                blackboard,
                Collections.emptyMap(),
                messageBus,
                runId,
                Collections.emptyMap()
        );

        // Mock RAG
        when(ragRetrievalService.retrieveForTask(any(), anyString()))
                .thenReturn(RagRetrievalResult.empty());
        when(distributedHandoffExecutor.isActive()).thenReturn(false);

        // 所有 Agent 都返回成功
        Agent successResult = mock(Agent.class);
        when(successResult.execute(any(), any())).thenReturn(
                new AgentResult(true, "Success", List.of(), Map.of("verdict", "PASS"), List.of())
        );

        // 使用统一的 mock 来处理多个 specialist
        agentRegistry.put("MindMapAgent", successResult);
        agentRegistry.put("ReadingAgent", successResult);
        agentRegistry.put("CodingAgent", successResult);
        agentRegistry.put("VisualizationAgent", successResult);

        when(plannerAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Plan", List.of(), Map.of(), List.of())
        );
        when(docAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Doc", List.of(), Map.of("verdict", "PASS"), List.of())
        );
        when(quizAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Quiz", List.of(), Map.of("verdict", "PASS"), List.of())
        );
        when(criticAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Pass", List.of(), Map.of("verdict", "PASS"), List.of())
        );
        when(pathAgent.execute(any(), any())).thenReturn(
                new AgentResult(true, "Path", List.of(), Map.of(), List.of())
        );

        // When: 执行完整工作流
        AgentResult result = supervisorAgent.runFullWorkflow(task, context);

        // Then: 验证工作流完成
        assertTrue(result.success());
        assertEquals("COMPLETED", result.metadata().get("workflowState"));
        assertTrue((Integer) result.metadata().get("specialistsExecuted") > 0);

        // 验证所有消息都被发布到 MessageBus
        ArgumentCaptor<AgentMessage> messageCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageBus, atLeast(10)).publish(messageCaptor.capture());

        // 验证有 HANDOFF 和 RESULT 类型的消息
        List<AgentMessage> capturedMessages = messageCaptor.getAllValues();
        boolean hasHandoff = capturedMessages.stream()
                .anyMatch(m -> AgentMessageType.HANDOFF.equals(m.type()));
        boolean hasResult = capturedMessages.stream()
                .anyMatch(m -> AgentMessageType.ARTIFACT_READY.equals(m.type()));

        assertTrue(hasHandoff, "应该有 HANDOFF 类型的消息");
        assertTrue(hasResult, "应该有 ARTIFACT_READY 类型的消息");
    }
}
