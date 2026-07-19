package com.visionary.service;

import com.visionary.client.DashScopeTtsClient;
import com.visionary.client.XunfeiTtsClient;
import com.visionary.config.TtsProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @Test
    void failingSingleProviderEntersCooldownInsteadOfRetryingFromEveryCard() throws Exception {
        TtsProperties properties = new TtsProperties();
        properties.setFallbackEnabled(false);
        AudioCacheService cache = mock(AudioCacheService.class);
        DashScopeTtsClient dashScope = mock(DashScopeTtsClient.class);
        XunfeiTtsClient xunfei = mock(XunfeiTtsClient.class);
        when(dashScope.isConfigured()).thenReturn(true);
        when(cache.buildCacheKey(anyString(), anyString(), anyString(), anyString())).thenReturn("cache-key");
        when(cache.getCachedFile("cache-key")).thenReturn(Optional.empty());
        when(dashScope.synthesize(anyString(), anyString(), anyString()))
                .thenThrow(new IOException("provider offline"));
        TtsService service = new TtsService(properties, cache, dashScope, xunfei);

        assertThatThrownBy(() -> service.synthesize("测试语音", null, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.healthMessage()).isNotBlank().isNotEqualTo("READY");
    }
}
