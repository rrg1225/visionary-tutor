package com.visionary.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时打印各 AI 供应商 Key 是否已成功注入，便于排查「本地降级模板」问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProvidersStartupVerifier implements ApplicationRunner {

    private final AiApiConfig aiApiConfig;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[AI Providers] deepSeek={} dashScope={} xunfeiSpark={} (env file overrides empty IDE vars)",
                flag(aiApiConfig.isDeepSeekConfigured()),
                flag(aiApiConfig.isDashScopeConfigured()),
                flag(aiApiConfig.isXunfeiConfigured()));
        if (!aiApiConfig.isDeepSeekConfigured()) {
            log.warn("[AI Providers] DeepSeek 未配置 — 资源生成将使用本地降级模板。"
                    + "请确认 backend/.env.properties 存在且已重启；"
                    + "若 IDE Run Configuration 设置了空的 DEEPSEEK_API_KEY 请删除该项");
        }
    }

    private static String flag(boolean configured) {
        return configured ? "OK" : "MISSING";
    }
}
