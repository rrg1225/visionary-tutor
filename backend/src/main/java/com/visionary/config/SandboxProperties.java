package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.docker")
public class SandboxProperties {

    private boolean enabled = true;

    /**
     * Docker daemon URI. Empty = auto-detect (unix socket / Windows named pipe).
     */
    private String host = "";

    private String image = "python:3.10-slim";

    private int memoryMb = 256;

    private double cpus = 0.5;

    private int timeoutSeconds = 5;

    private boolean pullOnStart = true;

    public long memoryBytes() {
        return Math.max(64, memoryMb) * 1024L * 1024L;
    }

    public long nanoCpus() {
        return Math.max(1L, Math.round(Math.max(0.1D, cpus) * 1_000_000_000L));
    }
}
