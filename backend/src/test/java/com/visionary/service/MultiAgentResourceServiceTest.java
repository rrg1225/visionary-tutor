package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.core.*;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.agent.tool.ProfileMergeTool;
import com.visionary.os.PublishGate;
import com.visionary.os.PublishStatus;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.governance.CompositeScoreCalculator;
import com.visionary.repository.AgentRunStepRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.resourcegeneration.infrastructure.GenerationTraceService;
import com.visionary.resourcegeneration.application.CriticReviewDecision;
import com.visionary.resourcegeneration.application.CriticReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MultiAgentResourceService 单元测试
 * 使用 JUnit 5 和 Mockito，不启动 Spring Boot 容器
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiAgentResourceServiceTest {

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @Mock
    private LearningSessionRepository learningSessionRepository;

    @Mock
    private GeneratedArtifactRepository artifactRepository;

    @Mock
    private AgentRunStepRepository stepRepository;

    @Mock
    private PersistenceManager persistenceManager;

    @Mock
    private LocalMockService localMockService;

    @Mock
    private CitationValidator citationValidator;

    @Mock
    private PublishGate publishGate;

    @Mock
    private ContentSafetyFilter contentSafetyFilter;

    @Mock
    private AgentDispatcher agentDispatcher;

    @Mock
    private ProfileMergeTool profileMergeTool;

    @Mock
    private GovernanceTraceService governanceTraceService;

    @Mock
    private com.visionary.config.GovernanceProperties governanceProperties;

    @Mock
    private CompositeScoreCalculator compositeScoreCalculator;

    @Mock
    private GenerationFallbackService generationFallbackService;

    @Mock
    private GovernanceQualityGateService governanceQualityGateService;

    @Mock
    private GenerationTraceService generationTraceService;

    @Mock
    private CriticReviewService criticReviewService;

    @InjectMocks
    private LegacyGenerationEngine multiAgentResourceService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(multiAgentResourceService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(multiAgentResourceService, "agentMode", "legacy");
        lenient().when(agentDispatcher.isAvailable()).thenReturn(false);
        lenient().when(persistenceManager.saveAndIndexArtifact(any())).thenAnswer(invocation -> {
            GeneratedArtifact artifact = invocation.getArgument(0);
            if (artifact.getId() == null) {
                artifact.setId(1L);
            }
            return artifact;
        });
        lenient().when(governanceQualityGateService.applyArtifactSafetyMetadata(
                any(), anyDouble(), anyString(), anyString()
        )).thenReturn(new GovernanceQualityGateService.SafetyReviewResult(true, "PASSED", "安全检查通过"));
        lenient().when(compositeScoreCalculator.computeCompositeScore(any(), anyDouble())).thenReturn(75.0D);
        lenient().when(governanceProperties.getMaxRevisionRounds()).thenReturn(5);
        lenient().when(criticReviewService.critique(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CriticReviewDecision(false, "Critic review passed"));
    }

    @Test
    void testGenerate_WithNormalRequest_ShouldCallAiClientAndDistributeTasks() throws Exception {
        // Given: 准备测试数据
        Long sessionId = 1L;
        String topic = "Java 并发编程";
        String profileSnapshot = """
                {"knowledgeBase": {"value": "了解基础语法", "confidence": 0.7}}
                """;

        ResourceGenerationRequest request = new ResourceGenerationRequest(
                sessionId,
                topic,
                profileSnapshot,
                "暂无薄弱点",
                "专注",
                List.of(GeneratedArtifact.ArtifactType.HANDOUT, GeneratedArtifact.ArtifactType.QUIZ)
        );

        LearningSession mockSession = new LearningSession();
        mockSession.setId(sessionId);
        mockSession.setTopic(topic);

        // Mock RAG 检索结果
        RagRetrievalResult mockRagResult = RagRetrievalResult.empty();

        // When: 配置 Mock 行为
        when(persistenceManager.requireSession(sessionId)).thenReturn(mockSession);
        when(localMockService.isEnabled()).thenReturn(false);
        when(ragRetrievalService.retrieveForTask(any(), anyString())).thenReturn(mockRagResult);
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        doAnswer(invocation -> {
            String system = invocation.getArgument(0);
            if (system.contains("FactualityVerifier")) {
                return "{\"factualityScore\":0.95,\"factualErrors\":[],\"hallucinationLog\":[]}";
            }
            if (system.contains("CriticAgent")) {
                return "{\"verdict\":\"PASS\",\"critique\":\"审查通过\"}";
            }
            String user = invocation.getArgument(1);
            if (user != null && user.contains("QUIZ")) {
                return generateMockQuizContent();
            }
            return generateMockHandoutContent();
        }).when(deepSeekApiClient).chat(anyString(), anyString(), anyBoolean());

        when(citationValidator.validate(anyString(), any())).thenReturn(
                new CitationValidator.ValidationResult("GROUNDED", "验证通过")
        );
        when(publishGate.evaluate(anyString(), any(), any())).thenReturn(
                new PublishGate.PublishDecision(PublishStatus.PUBLISHED, "GROUNDED", "验证通过", 0.9, "{}")
        );
        when(contentSafetyFilter.check(anyString(), anyDouble(), anyString())).thenReturn(
                new ContentSafetyFilter.SafetyResult(true, "PASSED", "安全检查通过", 0.0)
        );

        // 模拟制品保存
        when(persistenceManager.findSessionArtifacts(anyLong())).thenReturn(List.of());
        when(persistenceManager.findRunSteps(anyString())).thenReturn(List.of());

        // Then: 执行测试并验证
        ResourceGenerationResponse response = multiAgentResourceService.generate(request);

        // 验证 AI 客户端被正确调用
        verify(deepSeekApiClient, atLeastOnce()).isConfigured();
        verify(deepSeekApiClient, atLeastOnce()).chat(anyString(), anyString(), eq(false));

        // 验证 RAG 检索服务被调用
        verify(ragRetrievalService, atLeastOnce()).retrieveForTask(any(), anyString());
        verify(persistenceManager).saveStep(
                anyString(),
                eq(sessionId),
                eq("DocAgent"),
                anyInt(),
                contains("HANDOUT"),
                anyString(),
                anyString()
        );
        verify(persistenceManager, never()).saveStep(
                anyString(),
                eq(sessionId),
                eq("LectureAgent"),
                anyInt(),
                anyString(),
                anyString(),
                anyString()
        );

        // 验证响应
        assertNotNull(response);
        assertNotNull(response.runId());
    }

    @Test
    void testGenerate_WithDeepSeekNotConfigured_ShouldUseFallbackContent() throws Exception {
        // Given: DeepSeek 未配置的场景
        Long sessionId = 2L;
        String topic = "数据结构与算法";

        ResourceGenerationRequest request = new ResourceGenerationRequest(
                sessionId,
                topic,
                "{}",
                null,
                null,
                List.of(GeneratedArtifact.ArtifactType.HANDOUT)
        );

        LearningSession mockSession = new LearningSession();
        mockSession.setId(sessionId);
        mockSession.setTopic(topic);

        // When: DeepSeek 未配置
        when(persistenceManager.requireSession(sessionId)).thenReturn(mockSession);
        when(localMockService.isEnabled()).thenReturn(false);
        when(deepSeekApiClient.isConfigured()).thenReturn(false);
        when(ragRetrievalService.retrieveForTask(any(), anyString())).thenReturn(RagRetrievalResult.empty());

        when(citationValidator.validate(anyString(), any())).thenReturn(
                new CitationValidator.ValidationResult("NO_VALIDATOR", "引用校验器不可用")
        );
        when(publishGate.evaluate(anyString(), any(), any())).thenReturn(
                new PublishGate.PublishDecision(PublishStatus.PUBLISHED, "GROUNDED", "验证通过", 0.9, "{}")
        );
        when(contentSafetyFilter.check(anyString(), anyDouble(), anyString())).thenReturn(
                new ContentSafetyFilter.SafetyResult(true, "PASSED", "安全检查通过", 0.0)
        );

        when(persistenceManager.findSessionArtifacts(anyLong())).thenReturn(List.of());
        when(persistenceManager.findRunSteps(anyString())).thenReturn(List.of());

        // Then: 执行测试
        ResourceGenerationResponse response = multiAgentResourceService.generate(request);

        // 验证使用了降级内容（没有调用 AI 客户端）
        verify(deepSeekApiClient, never()).chat(anyString(), anyString(), anyBoolean());
        assertNotNull(response);
    }

    @Test
    void requestedMindMapUsesLiveModelEvenWhenKnowledgeBaseHasNoMatch() throws Exception {
        Long sessionId = 22L;
        ResourceGenerationRequest request = new ResourceGenerationRequest(
                sessionId,
                "梯度下降",
                "{}",
                null,
                null,
                List.of(GeneratedArtifact.ArtifactType.MINDMAP)
        );
        LearningSession session = new LearningSession();
        session.setId(sessionId);
        session.setTopic("梯度下降");

        when(persistenceManager.requireSession(sessionId)).thenReturn(session);
        when(localMockService.isEnabled()).thenReturn(false);
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        when(deepSeekApiClient.chat(anyString(), anyString(), eq(false))).thenReturn("""
                # 梯度下降知识导图
                ```mermaid
                mindmap
                  root((梯度下降))
                    目标函数
                    梯度
                    学习率
                    收敛判断
                ```
                """);
        when(ragRetrievalService.retrieveForTask(any(), anyString())).thenReturn(RagRetrievalResult.empty());
        when(citationValidator.validate(anyString(), any())).thenReturn(
                new CitationValidator.ValidationResult("NO_EVIDENCE", "未使用知识库补充材料")
        );
        when(publishGate.evaluate(anyString(), any(), any())).thenReturn(
                new PublishGate.PublishDecision(
                        PublishStatus.PUBLISHED,
                        "NO_EVIDENCE",
                        "未使用知识库补充材料",
                        0.0,
                        "{}"
                )
        );
        when(persistenceManager.findRunSteps(anyString())).thenReturn(List.of());

        ResourceGenerationResponse response = multiAgentResourceService.generate(request);

        assertEquals(1, response.artifacts().size());
        assertEquals(GeneratedArtifact.ArtifactType.MINDMAP, response.artifacts().get(0).getArtifactType());
        assertEquals("PUBLISHED", response.artifacts().get(0).getPublishStatus());
        assertTrue(response.artifacts().get(0).getContentMarkdown().contains("mindmap"));
        verify(generationFallbackService).ensureLiveGenerationMode(
                any(), eq("LEGACY_PIPELINE"), eq("MindMapAgent"));
        verify(generationFallbackService, never()).markGenerationMode(
                any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testListArtifacts_ShouldReturnVisibleArtifacts() {
        // Given
        Long sessionId = 3L;
        GeneratedArtifact artifact1 = new GeneratedArtifact();
        artifact1.setId(1L);
        artifact1.setArtifactType(GeneratedArtifact.ArtifactType.HANDOUT);
        artifact1.setPublishStatus("PUBLISHED");

        GeneratedArtifact artifact2 = new GeneratedArtifact();
        artifact2.setId(2L);
        artifact2.setArtifactType(GeneratedArtifact.ArtifactType.QUIZ);
        artifact2.setPublishStatus("BLOCKED"); // 应被过滤

        when(persistenceManager.listVisibleArtifacts(sessionId)).thenReturn(List.of(artifact1));

        // When
        List<GeneratedArtifact> result = multiAgentResourceService.listArtifacts(sessionId);

        // Then
        assertEquals(1, result.size());
        assertEquals(GeneratedArtifact.ArtifactType.HANDOUT, result.get(0).getArtifactType());
        verify(persistenceManager).listVisibleArtifacts(sessionId);
    }

    @Test
    void artifactTypesUseCanonicalSpecialistNames() {
        Map.of(
                GeneratedArtifact.ArtifactType.HANDOUT, "DocAgent",
                GeneratedArtifact.ArtifactType.QUIZ, "QuizAgent",
                GeneratedArtifact.ArtifactType.MINDMAP, "MindMapAgent",
                GeneratedArtifact.ArtifactType.LEARNING_PATH, "PathAgent",
                GeneratedArtifact.ArtifactType.CODE_PRACTICE, "CodingAgent",
                GeneratedArtifact.ArtifactType.EXTENDED_READING, "ReadingAgent",
                GeneratedArtifact.ArtifactType.VIDEO_SCRIPT, "VisualizationAgent",
                GeneratedArtifact.ArtifactType.VISUALIZATION, "VisualizationAgent"
        ).forEach((type, expected) -> assertEquals(expected, LegacyGenerationEngine.agentName(type)));
    }

    private String generateMockHandoutContent() {
        return """
                # Java 并发编程讲义

                ## 学习目标
                掌握 Java 并发编程基础概念

                ## 核心概念
                1. 线程与进程的区别
                2. synchronized 关键字
                3. volatile 内存可见性

                ## 示例代码
                ```java
                public class Counter {
                    private int count = 0;
                    public synchronized void increment() {
                        count++;
                    }
                }
                ```
                """;
    }

    private String generateMockQuizContent() {
        return """
                ## 练习题

                1. 什么是线程安全？
                A. 代码执行速度快
                B. 多线程环境下数据一致性
                C. 单线程执行
                答案: B

                2. synchronized 可以修饰哪些元素？
                A. 方法
                B. 代码块
                C. 以上都是
                答案: C
                """;
    }
}
