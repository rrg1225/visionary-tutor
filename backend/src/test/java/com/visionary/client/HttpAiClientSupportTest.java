package com.visionary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.AiApiConfig;
import com.visionary.service.LocalMockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpAiClientSupportTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesDemoServiceOnlyWhenAnAiRequestIsMade() throws Exception {
        AiApiConfig config = mock(AiApiConfig.class);
        ProviderCircuitBreaker circuitBreaker = mock(ProviderCircuitBreaker.class);
        ObjectProvider<LocalMockService> provider = mock(ObjectProvider.class);
        LocalMockService localMockService = mock(LocalMockService.class);
        when(config.getMaxRetries()).thenReturn(1);
        when(localMockService.isEnabled()).thenReturn(true);
        when(localMockService.openAiCompatibleResponse("api.example.com"))
                .thenReturn("{\"choices\":[]}");
        when(provider.getIfAvailable()).thenReturn(localMockService);

        HttpAiClientSupport support = new HttpAiClientSupport(
                config,
                new ObjectMapper(),
                circuitBreaker,
                provider
        );

        verify(provider, never()).getIfAvailable();
        String response = support.postJsonWithRetry(
                "https://api.example.com/v1/chat/completions",
                "demo-token",
                "{}"
        );

        assertThat(response).isEqualTo("{\"choices\":[]}");
        verify(provider).getIfAvailable();
        verify(localMockService).openAiCompatibleResponse("api.example.com");
    }
}
