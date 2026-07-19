package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAnswerCacheServiceTest {

    private ChatAnswerCacheService service;

    @BeforeEach
    void setUp() {
        service = new ChatAnswerCacheService(new ObjectMapper(), new DefaultResourceLoader());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(
                service,
                "seedResource",
                "classpath:demo-data/reviewed-chat-answers.json"
        );
        ReflectionTestUtils.setField(service, "maxEntries", 32);
        ReflectionTestUtils.setField(service, "ttlMinutes", 60L);
        service.loadReviewedAnswers();
    }

    @Test
    void servesReviewedCnnAnswerForNormalizedQuestion() {
        String answer = service.find(" 什么是 CNN？ ", "AUTO").orElseThrow();

        assertThat(answer)
                .contains("卷积神经网络")
                .contains("自检题")
                .doesNotContain("Demo Mode")
                .doesNotContain("降级");
    }

    @Test
    void doesNotCacheContextDependentFollowUp() {
        assertThat(service.isCacheable("把上面的内容换一种方式解释")).isFalse();
        assertThat(service.find("把上面的内容换一种方式解释", "AUTO")).isEmpty();
    }

    @Test
    void remembersSuccessfulStandaloneModelAnswer() {
        service.remember("什么是感受野", "AUTO", "感受野是特征所对应的输入区域。");

        assertThat(service.find("什么是感受野？", "AUTO"))
                .contains("感受野是特征所对应的输入区域。");
    }
}
