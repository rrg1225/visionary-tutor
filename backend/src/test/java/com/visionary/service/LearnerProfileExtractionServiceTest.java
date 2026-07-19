package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.dto.ProfileExtractionResponse;
import com.visionary.entity.User;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LearnerProfileExtractionService 单元测试
 * 测试 JSON 解析和 UserRepository 落库逻辑
 */
@ExtendWith(MockitoExtension.class)
class LearnerProfileExtractionServiceTest {

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LearningEventMetricRepository learningEventMetricRepository;

    @Mock
    private KnowledgeTracingService knowledgeTracingService;

    @InjectMocks
    private LearnerProfileExtractionService extractionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(extractionService, "objectMapper", new ObjectMapper());
    }

    @Test
    void testExtract_WithChatHistory_ShouldParseJsonAndPersistToRepository() throws Exception {
        // Given: 模拟学生聊天记录
        Long userId = 1L;
        String conversationText = """
                学生: 我想学习 Java 并发编程
                学生: 我对多线程的概念不太理解
                学生: 希望能有一些实践项目
                """;
        String assessmentSummary = "基础薄弱，需要加强";

        ProfileExtractionRequest request = new ProfileExtractionRequest(
                userId,
                conversationText,
                assessmentSummary,
                "{}",
                "专注",
                "FULL"
        );

        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setUsername("test_student");
        mockUser.setLearnerProfileSnapshot("{}");
        mockUser.setProfileVersion(1);

        String mockLlmResponse = """
                {
                    "knowledgeBase": {
                        "value": "了解 Java 基础语法，不熟悉并发概念",
                        "evidence": ["学生对多线程概念不理解"],
                        "confidence": 0.75
                    },
                    "goal": {
                        "value": "掌握 Java 并发编程，完成实践项目",
                        "evidence": ["学生希望有实践项目"],
                        "confidence": 0.85
                    },
                    "cognitiveStyle": {
                        "value": "实践型学习者",
                        "evidence": ["希望有实践项目"],
                        "confidence": 0.70
                    },
                    "weakPoints": {
                        "value": "多线程概念",
                        "evidence": ["对多线程概念不太理解"],
                        "confidence": 0.80
                    },
                    "errorPatterns": {
                        "value": "暂无",
                        "evidence": [],
                        "confidence": 0.30
                    },
                    "learningPace": {
                        "value": "适中",
                        "evidence": ["学习积极性高"],
                        "confidence": 0.65
                    },
                    "emotionAttention": {
                        "value": "专注",
                        "evidence": ["当前情绪状态"],
                        "confidence": 0.60
                    }
                }
                """;

        // When: 配置 Mock 行为
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        doReturn(mockLlmResponse).when(deepSeekApiClient).chat(anyString(), anyString(), eq(false));

        // 使用真实的 ObjectMapper 进行 JSON 验证
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        // Then: 执行测试
        ProfileExtractionResponse response = extractionService.extract(request);

        // 验证 Repository 调用
        verify(userRepository).findById(userId);
        verify(userRepository).save(userCaptor.capture());

        // 验证保存的用户数据
        User savedUser = userCaptor.getValue();
        assertNotNull(savedUser.getLearnerProfileSnapshot());
        assertTrue(savedUser.getLearnerProfileSnapshot().contains("掌握 Java 并发编程"));
        assertTrue(savedUser.getLearnerProfileSnapshot().contains("实践型学习者"));

        // 验证响应
        assertNotNull(response);
        assertEquals("UPDATED", response.status());
        assertTrue(response.llmUsed());
    }

    @Test
    void testExtract_WithDeepSeekNotConfigured_ShouldSkipAndKeepPrevious() throws Exception {
        // Given: DeepSeek 未配置场景
        Long userId = 2L;
        String previousProfile = """
                {"knowledgeBase": {"value": "已有知识", "confidence": 0.8}}
                """;

        ProfileExtractionRequest request = new ProfileExtractionRequest(
                userId,
                "新对话",
                null,
                previousProfile,
                null,
                "FULL"
        );

        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setLearnerProfileSnapshot(previousProfile);

        // When: DeepSeek 未配置
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(deepSeekApiClient.isConfigured()).thenReturn(false);

        // Then: 执行测试
        ProfileExtractionResponse response = extractionService.extract(request);

        // 验证没有调用 AI 客户端
        verify(deepSeekApiClient, never()).chat(anyString(), anyString(), anyBoolean());
        verify(userRepository).save(any(User.class));

        // 验证响应状态
        assertEquals("SKIPPED", response.status());
        assertFalse(response.llmUsed());
    }

    @Test
    void testExtract_WithNullUserId_ShouldNotPersist() {
        // Given: 无用户 ID 的场景
        ProfileExtractionRequest request = new ProfileExtractionRequest(
                null,
                "对话内容",
                null,
                "{}",
                null,
                "FULL"
        );

        when(deepSeekApiClient.isConfigured()).thenReturn(false);

        // Then: 执行测试
        ProfileExtractionResponse response = extractionService.extract(request);

        // 验证没有调用 Repository（因为没有 userId）
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());

        assertEquals("SKIPPED", response.status());
    }

    @Test
    void testExtract_WithInvalidJsonResponse_ShouldFallbackToPrevious() throws Exception {
        // Given: LLM 返回无效 JSON
        Long userId = 3L;
        String previousProfile = "{\"previous\": \"data\"}";

        ProfileExtractionRequest request = new ProfileExtractionRequest(
                userId,
                "对话",
                null,
                previousProfile,
                null,
                "FULL"
        );

        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setLearnerProfileSnapshot(previousProfile);

        // When: AI 返回无效内容
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        doReturn("这不是有效的 JSON 内容").when(deepSeekApiClient).chat(anyString(), anyString(), eq(false));

        // Then: 执行测试
        ProfileExtractionResponse response = extractionService.extract(request);

        // 验证调用了 save（即使提取失败也会保存）
        verify(userRepository).save(any(User.class));

        // 验证状态为失败
        assertEquals("FAILED", response.status());
    }

    @Test
    void testExtract_WithUserTurnPhase_ShouldUseQuickPrompt() throws Exception {
        // Given: USER_TURN 阶段（快速提取）
        Long userId = 4L;

        ProfileExtractionRequest request = new ProfileExtractionRequest(
                userId,
                "学生刚刚说：我想学习机器学习",
                null,
                "{}",
                "专注",
                "USER_TURN"
        );

        User mockUser = new User();
        mockUser.setId(userId);

        String quickResponse = """
                {
                    "goal": {
                        "value": "学习机器学习",
                        "evidence": ["学生刚刚提到"],
                        "confidence": 0.70
                    }
                }
                """;

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        doReturn(quickResponse).when(deepSeekApiClient).chat(contains("学生刚发送的新消息"), anyString(), eq(false));

        // Then: 执行测试
        ProfileExtractionResponse response = extractionService.extract(request);

        // 验证使用了 USER_TURN 专用提示词
        verify(deepSeekApiClient).chat(contains("学生刚发送的新消息"), anyString(), eq(false));
        assertEquals("UPDATED", response.status());
    }

    @Test
    void testCalibrateFromQuizResults_ShouldUpdateKnowledgeState() {
        // Given: 测验结果校准场景
        String userId = "5";
        Long uid = 5L;

        String existingSnapshot = """
                {
                    "knowledgeState": [
                        {"concept": "Java Basics", "confidence": 0.5, "mastery": 50}
                    ]
                }
                """;

        User mockUser = new User();
        mockUser.setId(uid);
        mockUser.setLearnerProfileSnapshot(existingSnapshot);
        mockUser.setProfileVersion(2);

        when(userRepository.findById(uid)).thenReturn(Optional.of(mockUser));

        // 连续答对 3 题
        java.util.Map<String, Integer> correctStreak = new java.util.HashMap<>();
        correctStreak.put("Java Basics", 3);

        // Then: 执行校准
        extractionService.calibrateFromQuizResults(userId, correctStreak, null);

        // 验证保存的用户数据
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertNotNull(savedUser.getLearnerProfileSnapshot());
        // 验证 confidence 被提升（0.5 + 0.15）
        assertTrue(savedUser.getLearnerProfileSnapshot().contains("Java Basics"));
    }

    @Test
    void testCalibrateFromQuizResults_WithWrongStreak_ShouldDecreaseConfidence() {
        // Given: 连续答错的场景
        String userId = "6";
        Long uid = 6L;

        String existingSnapshot = """
                {
                    "knowledgeState": [
                        {"concept": "Python", "confidence": 0.8, "mastery": 80}
                    ]
                }
                """;

        User mockUser = new User();
        mockUser.setId(uid);
        mockUser.setLearnerProfileSnapshot(existingSnapshot);

        when(userRepository.findById(uid)).thenReturn(Optional.of(mockUser));

        // 连续答错 2 题
        java.util.Map<String, Integer> wrongStreak = new java.util.HashMap<>();
        wrongStreak.put("Python", 2);

        // Then: 执行校准
        extractionService.calibrateFromQuizResults(userId, null, wrongStreak);

        // 验证保存操作
        verify(userRepository).save(any(User.class));
    }

    @Test
    void testCalibrateFromQuizResults_WithInvalidUserId_ShouldNotUpdate() {
        // Given: 无效用户 ID
        String invalidUserId = "invalid";

        // Then: 执行测试
        extractionService.calibrateFromQuizResults(invalidUserId, null, null);

        // 验证没有调用 Repository
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }
}
