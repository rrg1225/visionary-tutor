package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "visionary.tts")
public class TtsProperties {

    private boolean enabled = true;
    private String primaryProvider = "dashscope";
    private boolean fallbackEnabled = true;
    private int maxTextLength = 4000;
    private String defaultVoice = "longxiaochun";
    private double defaultSpeed = 1.0;
    private String defaultFormat = "mp3";

    private Cache cache = new Cache();
    private DashScope dashscope = new DashScope();
    private Xunfei xunfei = new Xunfei();

    @Data
    public static class Cache {
        private boolean enabled = true;
        private int redisTtlDays = 30;
        private String storageDir = "./storage/tts";
    }

    @Data
    public static class DashScope {
        private String model = "cosyvoice-v1";
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-to-speech/synthesis";
    }

    @Data
    public static class Xunfei {
        private String appId = "";
        private String apiKey = "";
        private String apiSecret = "";
        private String voice = "xiaoyan";
        private String url = "wss://tts-api.xfyun.cn/v2/tts";
        private String aue = "lame";
        private int connectTimeoutSeconds = 15;
        private int responseTimeoutSeconds = 30;
        private int maxAudioBytes = 10 * 1024 * 1024;
    }
}
