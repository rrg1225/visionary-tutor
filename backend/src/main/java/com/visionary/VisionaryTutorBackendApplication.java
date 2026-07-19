package com.visionary;

import com.visionary.config.AiApiConfig;
import com.visionary.config.LocalEnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AiApiConfig.class)
@EnableJpaAuditing
@EnableScheduling
public class VisionaryTutorBackendApplication {

    public static void main(String[] args) {
        LocalEnvFileLoader.applyToSystemProperties(LocalEnvFileLoader.loadFirstAvailable());
        SpringApplication.run(VisionaryTutorBackendApplication.class, args);
    }
}
