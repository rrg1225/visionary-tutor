package com.visionary.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.visionary.config.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerSandboxExecutor {

    private final SandboxProperties properties;
    private final AtomicBoolean dockerReady = new AtomicBoolean(false);
    private volatile String readinessReason = "Sandbox is initializing";

    @Autowired(required = false)
    private DockerClient dockerClient;

    @PostConstruct
    void verifyDocker() {
        if (!properties.isEnabled() || dockerClient == null) {
            readinessReason = properties.isEnabled()
                    ? "Docker client is unavailable" : "Sandbox is disabled";
            log.warn("Docker sandbox disabled or DockerClient bean unavailable");
            return;
        }
        try {
            dockerClient.pingCmd().exec();
            try {
                dockerClient.inspectImageCmd(properties.getImage()).exec();
            } catch (Exception imageMissing) {
                if (!properties.isPullOnStart()) {
                    throw imageMissing;
                }
                log.info("Sandbox image {} is missing; pulling it before enabling execution", properties.getImage());
                dockerClient.pullImageCmd(properties.getImage()).start().awaitCompletion(120, TimeUnit.SECONDS);
                dockerClient.inspectImageCmd(properties.getImage()).exec();
            }
            dockerReady.set(true);
            readinessReason = "READY";
            log.info("Docker sandbox ready: image={}, memoryMb={}, cpus={}, timeout={}s",
                    properties.getImage(), properties.getMemoryMb(), properties.getCpus(), properties.getTimeoutSeconds());
        } catch (Exception e) {
            log.warn("Docker sandbox unavailable, code execution will degrade gracefully: {}", e.getMessage());
            dockerReady.set(false);
            readinessReason = "Sandbox image or Docker engine is unavailable: " + e.getMessage();
        }
    }

    public boolean isReady() {
        return properties.isEnabled() && dockerClient != null && dockerReady.get();
    }

    public String readinessReason() {
        return readinessReason;
    }

    public SandboxExecutionResult executePython(String code) {
        long startedAt = System.currentTimeMillis();
        if (code == null || code.isBlank()) {
            return SandboxExecutionResult.error("Empty code snippet.", elapsed(startedAt));
        }
        if (!isReady()) {
            return SandboxExecutionResult.unavailable(readinessReason, elapsed(startedAt));
        }

        String containerId = null;
        try {
            String launcher = buildPythonLauncher(code);
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode("none")
                    .withMemory(properties.memoryBytes())
                    .withMemorySwap(properties.memoryBytes())
                    .withNanoCPUs(properties.nanoCpus())
                    .withAutoRemove(false);

            CreateContainerResponse created = dockerClient.createContainerCmd(properties.getImage())
                    .withHostConfig(hostConfig)
                    .withCmd("python", "-c", launcher)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            containerId = created.getId();
            dockerClient.startContainerCmd(containerId).exec();

            LogCollector logCollector = new LogCollector();
            try (ResultCallback.Adapter<Frame> logCallback = logCollector) {
                dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(true)
                        .exec(logCallback);

                WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
                dockerClient.waitContainerCmd(containerId).exec(waitCallback);

                Integer exitCode;
                try {
                    exitCode = waitCallback.awaitStatusCode(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (DockerClientException e) {
                    forceKill(containerId);
                    return SandboxExecutionResult.timeout(
                            trim(logCollector.stdout()),
                            trim(logCollector.stderr()),
                            elapsed(startedAt)
                    );
                }
                logCallback.awaitCompletion(2, TimeUnit.SECONDS);

                String stdout = trim(logCollector.stdout());
                String stderr = trim(logCollector.stderr());
                if (exitCode != null && exitCode == 0) {
                    return SandboxExecutionResult.success(stdout, stderr, elapsed(startedAt));
                }
                String error = stderr.isBlank()
                        ? "Process exited with code " + exitCode
                        : stderr;
                return SandboxExecutionResult.error(stdout, error, elapsed(startedAt));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            forceKill(containerId);
            return SandboxExecutionResult.timeout("", "Sandbox execution interrupted.", elapsed(startedAt));
        } catch (Exception e) {
            log.warn("Docker sandbox execution failed: {}", e.getMessage());
            forceKill(containerId);
            return SandboxExecutionResult.error("", "Sandbox execution failed: " + e.getMessage(), elapsed(startedAt));
        } finally {
            removeContainer(containerId);
        }
    }

    private void forceKill(String containerId) {
        if (containerId == null || dockerClient == null) {
            return;
        }
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.debug("Failed to kill sandbox container {}: {}", containerId, e.getMessage());
        }
    }

    private void removeContainer(String containerId) {
        if (containerId == null || dockerClient == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.debug("Failed to remove sandbox container {}: {}", containerId, e.getMessage());
        }
    }

    private static String buildPythonLauncher(String code) {
        String encoded = Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
        return "import base64; exec(base64.b64decode('" + encoded + "').decode('utf-8'))";
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class LogCollector extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        @Override
        public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) {
                return;
            }
            if (StreamType.STDERR.equals(frame.getStreamType())) {
                stderr.write(frame.getPayload(), 0, frame.getPayload().length);
            } else {
                stdout.write(frame.getPayload(), 0, frame.getPayload().length);
            }
        }

        String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }
}
