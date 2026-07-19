package com.visionary.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sandbox.docker.enabled", havingValue = "true", matchIfMissing = true)
public class DockerSandboxConfig {

    @Bean(destroyMethod = "close")
    public DockerClient dockerClient(SandboxProperties properties) {
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (properties.getHost() != null && !properties.getHost().isBlank()) {
            builder.withDockerHost(properties.getHost());
        }
        DefaultDockerClientConfig config = builder.build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(20)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        log.info("Docker sandbox client configured: host={}", config.getDockerHost());
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
