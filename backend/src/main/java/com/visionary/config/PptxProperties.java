package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PPTX 导出配置，与 application.yml 中 pptx.* 对齐。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pptx")
public class PptxProperties {

    private Python python = new Python();
    private Script script = new Script();
    private Premium premium = new Premium();
    private Template template = new Template();

    @Data
    public static class Python {
        private String path = "python";
    }

    @Data
    public static class Script {
        private String path = "../ai_engine/pptx_generator.py";
    }

    @Data
    public static class Premium {
        private boolean enabled = true;
        private ScriptPath script = new ScriptPath();
        private int timeoutSeconds = 18;

        @Data
        public static class ScriptPath {
            private String path = "../ai_engine/pptx_premium_exporter.py";
        }
    }

    @Data
    public static class Template {
        private String dir = "../ai_engine/templates";
        private String baseName = "visionary-deck-v1";
        private boolean startupVerify = true;
    }

    /** 便捷访问：Python 可执行路径 */
    public String getPythonPath() {
        return python.getPath();
    }

    /** 便捷访问：标准版脚本路径 */
    public String getScriptPath() {
        return script.getPath();
    }

    /** 便捷访问：Premium 脚本路径 */
    public String getPremiumScriptPath() {
        return premium.getScript().getPath();
    }
}
