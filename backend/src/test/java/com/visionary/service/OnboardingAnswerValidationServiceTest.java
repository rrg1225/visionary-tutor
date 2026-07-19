package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.OnboardingAnswerValidationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingAnswerValidationServiceTest {

    private final DeepSeekApiClient client = mock(DeepSeekApiClient.class);
    private final OnboardingAnswerValidationService service =
            new OnboardingAnswerValidationService(client, new ObjectMapper());

    @Test
    void rejectsUnrelatedAndLowInformationAnswersWithoutCallingAi() {
        var request = new OnboardingAnswerValidationRequest(0, "你在学什么？", "今天天气很好");

        var response = service.validate(request);

        assertFalse(response.valid());
        assertFalse(response.aiUsed());
    }

    @Test
    void acceptsDeterministicallyRelevantAnswer() {
        var request = new OnboardingAnswerValidationRequest(
                1,
                "你的知识基础如何？",
                "我有 Python 和线代基础，但还没有系统学过机器学习"
        );

        assertTrue(service.validate(request).valid());
    }

    @Test
    void asksAiForAmbiguousMeaningfulAnswer() throws Exception {
        when(client.isConfigured()).thenReturn(true);
        when(client.chat(anyString(), anyString(), eq(false)))
                .thenReturn("{\"relevant\":true,\"reason\":\"描述了可投入时间\",\"confidence\":0.91}");
        var request = new OnboardingAnswerValidationRequest(
                3,
                "你希望学习节奏怎样？",
                "工作日晚上腾出一些空档，周末投入更多"
        );

        var response = service.validate(request);

        assertTrue(response.valid());
        assertTrue(response.aiUsed());
        verify(client).chat(anyString(), anyString(), eq(false));
    }
}
