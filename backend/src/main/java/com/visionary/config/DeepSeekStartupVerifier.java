package com.visionary.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekStartupVerifier implements ApplicationRunner {

    private final AiApiConfig aiApiConfig;

    @Override
    public void run(ApplicationArguments args) {
        if (aiApiConfig.isDeepSeekConfigured()) {
            log.info("DeepSeek API configured (baseUrl={}, model={})",
                    aiApiConfig.getDeepSeekBaseUrl(),
                    aiApiConfig.getDeepSeekChatModel());
        } else {
            log.warn("DeepSeek API key missing — chat will use local fallback templates. "
                    + "Set DEEPSEEK_API_KEY in backend/.env.properties and restart.");
        }
    }
}
