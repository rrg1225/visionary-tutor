package com.visionary.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, non-sensitive build identity used to detect frontend/backend version skew.
 */
@RestController
public class BuildMetadataController {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final String gitSha;
    private final String apiVersion;

    public BuildMetadataController(
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${APP_GIT_SHA:${BUILD_GIT_SHA:unknown}}") String gitSha,
            @Value("${APP_API_VERSION:2026-07-17}") String apiVersion
    ) {
        this.buildProperties = buildProperties;
        this.gitSha = gitSha;
        this.apiVersion = apiVersion;
    }

    @GetMapping("/api/meta/build")
    public Map<String, Object> build() {
        BuildProperties properties = buildProperties.getIfAvailable();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", "visionary-tutor-backend");
        payload.put("apiVersion", apiVersion);
        payload.put("gitSha", gitSha);
        payload.put("version", properties == null ? "development" : properties.getVersion());
        payload.put("buildTime", properties == null || properties.getTime() == null
                ? "development"
                : properties.getTime().toString());
        payload.put("serverTime", Instant.now().toString());
        return payload;
    }
}
