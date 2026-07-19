package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.api")
public class AiApiConfig {

    private String xunfeiSparkMaxKey;

    private String xunfeiSparkVoiceKey;

    private String deepSeekKey;

    private String qwenVlMaxKey;

    private String deepSeekBaseUrl = "https://api.deepseek.com";

    private String deepSeekChatModel = "deepseek-chat";

    private String deepSeekReasonerModel = "deepseek-reasoner";

    private String qwenBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String qwenVlModel = "qwen-vl-max";

    private String dashScopeKey;

    private String dashScopeImageUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

    private String dashScopeTaskUrl = "https://dashscope.aliyuncs.com/api/v1/tasks";

    private String dashScopeImageModel = "wanx-v1";

    private String dashScopeRerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private String dashScopeRerankModel = "gte-rerank";

    private String xunfeiSparkBaseUrl = "https://spark-api-open.xf-yun.com/v4";

    private String xunfeiSparkModel = "generalv3.5";

    private int connectTimeoutSeconds = 15;

    private int readTimeoutSeconds = 90;

    private int writeTimeoutSeconds = 30;

    private int maxRetries = 3;

    private long retryBackoffMs = 500;

    public boolean isDeepSeekConfigured() {
        return deepSeekKey != null && !deepSeekKey.isBlank();
    }

    public boolean isQwenConfigured() {
        return qwenVlMaxKey != null && !qwenVlMaxKey.isBlank();
    }

    public boolean isDashScopeConfigured() {
        return dashScopeKey != null && !dashScopeKey.isBlank();
    }

    public boolean isXunfeiConfigured() {
        return (xunfeiSparkMaxKey != null && !xunfeiSparkMaxKey.isBlank())
                || (xunfeiSparkVoiceKey != null && !xunfeiSparkVoiceKey.isBlank());
    }
}
