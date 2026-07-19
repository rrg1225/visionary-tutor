package com.visionary.config;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.LearningSession;
import com.visionary.entity.User;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.UserRepository;
import com.visionary.service.LocalMockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Demo Profile 启动预热：在空库时注入脱敏预置资源，避免答辩现场首屏空态。
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "visionary.demo-mode", name = "warmup-on-startup", havingValue = "true")
public class DemoWarmupRunner implements ApplicationRunner {

    private static final String DEMO_PROFILE_SNAPSHOT = """
            {"gradeLevel":"大二","learningStyle":"visual+practical",\
            "goal":"理解 CNN 卷积、Padding 与 Stride","weakPoints":["卷积尺寸推导","反向传播链式法则"],\
            "onboardingComplete":true,"source":"DEMO_WARMUP"}""";
    private static final String DEMO_WEAK_POINTS = "[\"卷积尺寸推导\",\"Padding/Stride\",\"反向传播\"]";
    private static final String DEMO_EMOTION_SNAPSHOT = "专注 / 稳定";

    private final GeneratedArtifactRepository artifactRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final UserRepository userRepository;
    private final LocalMockService localMockService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    @Value("${visionary.demo-mode.warmup-username:demo_learner}")
    private String warmupUsername;

    @Value("${visionary.demo-mode.warmup-topic:计算机视觉 - CNN 卷积、Padding/Stride 专题}")
    private String warmupTopic;

    @Override
    public void run(ApplicationArguments args) {
        if (!localMockService.isEnabled()) {
            log.info("[demo-warmup] 已跳过：demo-mode.enabled=false");
            return;
        }
        if (artifactRepository.count() > 0) {
            log.info("[demo-warmup] 已跳过：数据库已有 {} 条演示资源", artifactRepository.count());
            return;
        }

        transactionTemplate.executeWithoutResult(status -> seedDemoArtifacts());
    }

    private void seedDemoArtifacts() {
        User demoUser = userRepository.findByUsername(warmupUsername)
                .orElseGet(this::createDemoUser);

        LearningSession session = resolveDemoSession(demoUser.getId());
        String runId = "demo-warmup-" + UUID.randomUUID();

        var response = localMockService.generateResources(
                runId,
                session,
                warmupTopic,
                new ResourceGenerationRequest(
                        session.getId(),
                        warmupTopic,
                        DEMO_PROFILE_SNAPSHOT,
                        DEMO_WEAK_POINTS,
                        DEMO_EMOTION_SNAPSHOT,
                        null
                )
        );

        log.info(
                "[demo-warmup] 预热完成：user={}, sessionId={}, artifacts={}, message={}",
                demoUser.getUsername(),
                session.getId(),
                response.artifacts() != null ? response.artifacts().size() : 0,
                response.reviewSummary()
        );
    }

    private User createDemoUser() {
        User user = new User();
        user.setUsername(warmupUsername);
        user.setPassword(passwordEncoder.encode("Demo@2026"));
        user.setEmail(warmupUsername + "@demo.local");
        user.setDisplayName("演示学员");
        user.setLearningGoal(warmupTopic);
        user.setLearnerProfileSnapshot(DEMO_PROFILE_SNAPSHOT);
        user.setStatus(User.UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        log.info("[demo-warmup] 已创建演示账号：username={}", saved.getUsername());
        return saved;
    }

    private LearningSession resolveDemoSession(Long userId) {
        List<LearningSession> sessions = learningSessionRepository.findByUserIdOrderByGmtCreatedDesc(userId);
        if (!sessions.isEmpty()) {
            return sessions.get(0);
        }

        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setTopic(warmupTopic);
        session.setStatus(LearningSession.SessionStatus.ACTIVE);
        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        session.setConversationSummary("Demo warmup seeded session");
        LearningSession saved = learningSessionRepository.save(session);
        log.info("[demo-warmup] 已创建演示会话：sessionId={}", saved.getId());
        return saved;
    }
}
