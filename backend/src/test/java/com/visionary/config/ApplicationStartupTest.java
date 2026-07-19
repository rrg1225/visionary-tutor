package com.visionary.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.config.import=",
                "spring.datasource.url=jdbc:h2:mem:startup;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6379",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "spring.flyway.enabled=false",
                "spring.main.allow-circular-references=false",
                "aliyun.oss.access-key-id=",
                "aliyun.oss.access-key-secret=",
                "ai.api.deepSeekKey=",
                "ai.api.qwenVlMaxKey=",
                "ai.api.dashScopeKey=",
                "ai.api.zhipuKey=",
                "ai.api.xunfeiSparkMaxKey=",
                "ai.api.xunfeiSparkVoiceKey=",
                "ai.xunfei.app-id=",
                "ai.xunfei.api-secret=",
                "vector.db.enabled=false",
                "visionary.rag-enabled=false",
                "visionary.demo-mode.enabled=false",
                "visionary.demo-mode.warmup-on-startup=false",
                "visionary.showcase.seed-on-startup=false",
                "visionary.recommendation.scheduled-push-enabled=false",
                "visionary.learning-os.async-remediation=false",
                "visionary.tts.enabled=false",
                "sandbox.docker.enabled=false",
                "agent.mcp.enabled=false"
        }
)
class ApplicationStartupTest {

    @Test
    void applicationContextStartsWithoutCircularDependencies() {
        // Context creation is the assertion.
    }
}
