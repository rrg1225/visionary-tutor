package com.visionary.service;

import com.visionary.dto.ResourceGenerationProgressEvent;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

@Service
public class ResourceGenerationJobService {

    private static final int MAX_EVENTS_PER_JOB = 240;
    private static final int DEFAULT_ESTIMATE_SECONDS = 120;
    private static final int MAX_ESTIMATE_SECONDS = 240;
    private static final int MAX_RETAINED_JOBS = 200;
    private static final Duration TERMINAL_RETENTION = Duration.ofHours(2);

    private final ResourceGenerationFacade resourceService;
    private final Executor executor;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public ResourceGenerationJobService(
            ResourceGenerationFacade resourceService,
            @Qualifier("sseStreamExecutor") Executor executor
    ) {
        this.resourceService = resourceService;
        this.executor = executor;
    }

    public JobSnapshot start(ResourceGenerationRequest request) {
        if (request == null || request.learningSessionId() == null) {
            throw new IllegalArgumentException("learningSessionId is required");
        }
        cleanupFinishedJobs();
        return startInternal(request);
    }

    public JobSnapshot retry(String taskId) {
        JobState previous = jobs.get(taskId);
        if (previous == null) {
            return notFound(taskId);
        }
        if (!isRetryable(previous.status)) {
            throw new IllegalStateException("Only failed or cancelled jobs can be retried");
        }
        ResourceGenerationRequest source = previous.request;
        ResourceGenerationRequest retryRequest = new ResourceGenerationRequest(
                source.learningSessionId(),
                source.topic(),
                source.learnerProfileSnapshot(),
                source.weakPointsSnapshot(),
                source.emotionSnapshot(),
                source.resourceTypes(),
                "retry-" + UUID.randomUUID()
        );
        cleanupFinishedJobs();
        return startInternal(retryRequest);
    }

    public JobSnapshot cancel(String taskId) {
        JobState state = jobs.get(taskId);
        if (state == null) {
            return notFound(taskId);
        }
        if (isTerminal(state.status)) {
            return snapshot(state);
        }
        state.cancelRequested = true;
        state.status = "CANCELED";
        state.message = "资源生成任务已取消";
        state.finishedAt = Instant.now();
        FutureTask<Void> task = state.task;
        if (task != null) {
            task.cancel(true);
        }
        return snapshot(state);
    }

    private JobSnapshot startInternal(ResourceGenerationRequest request) {
        String taskId = "res-" + UUID.randomUUID().toString().substring(0, 12);
        JobState state = new JobState(taskId, request);
        jobs.put(taskId, state);
        FutureTask<Void> task = new FutureTask<>(() -> {
            runJob(state, request);
            return null;
        });
        state.task = task;
        executor.execute(task);
        return snapshot(state);
    }

    public JobSnapshot get(String taskId) {
        JobState state = jobs.get(taskId);
        if (state == null) {
            return notFound(taskId);
        }
        return snapshot(state);
    }

    private void runJob(JobState state, ResourceGenerationRequest request) {
        if (state.cancelRequested) {
            return;
        }
        state.startedAt = Instant.now();
        state.status = "RUNNING";
        state.message = "资源生成任务已进入后台执行";
        try {
            ResourceGenerationResponse response = resourceService.generate(request, event -> {
                if (state.cancelRequested || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("resource generation cancelled");
                }
                state.progressPercent = Math.max(state.progressPercent, event.progressPercent());
                state.message = event.message();
                state.runId = event.runId();
                appendEvent(state, event);
            });
            if (state.cancelRequested || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("resource generation cancelled");
            }
            state.response = response;
            state.runId = response.runId();
            state.status = "SUCCEEDED";
            state.progressPercent = 100;
            state.message = response.reviewSummary();
            state.finishedAt = Instant.now();
        } catch (CancellationException e) {
            state.status = "CANCELED";
            state.message = "资源生成任务已取消";
            state.error = null;
            state.finishedAt = Instant.now();
        } catch (Exception e) {
            if (state.cancelRequested) {
                state.status = "CANCELED";
                state.message = "资源生成任务已取消";
                state.error = null;
                state.finishedAt = Instant.now();
                return;
            }
            state.status = "FAILED";
            state.message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "资源生成失败，可重试" : e.getMessage();
            state.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            state.finishedAt = Instant.now();
        }
    }

    private void appendEvent(JobState state, ResourceGenerationProgressEvent event) {
        synchronized (state.events) {
            state.events.add(event);
            if (state.events.size() > MAX_EVENTS_PER_JOB) {
                state.events.remove(0);
            }
        }
    }

    private JobSnapshot snapshot(JobState state) {
        List<ResourceGenerationProgressEvent> events;
        synchronized (state.events) {
            events = List.copyOf(state.events);
        }
        return new JobSnapshot(
                state.taskId,
                state.request.learningSessionId(),
                state.status,
                state.progressPercent,
                state.message,
                state.runId,
                events,
                state.response,
                state.error,
                state.createdAt,
                state.startedAt,
                state.finishedAt,
                estimateRemainingSeconds(state),
                isRetryable(state.status)
        );
    }

    private JobSnapshot notFound(String taskId) {
        return new JobSnapshot(
                taskId, null, "NOT_FOUND", 0, "任务不存在", null, List.of(), null, null,
                null, null, null, 0, false
        );
    }

    private int estimateRemainingSeconds(JobState state) {
        if (isTerminal(state.status)) {
            return 0;
        }
        Instant start = state.startedAt != null ? state.startedAt : state.createdAt;
        long elapsed = Math.max(0, Duration.between(start, Instant.now()).getSeconds());
        if (state.progressPercent >= 5) {
            long remaining = elapsed * (100L - state.progressPercent) / state.progressPercent;
            return (int) Math.max(5, Math.min(MAX_ESTIMATE_SECONDS, remaining));
        }
        return (int) Math.max(5, DEFAULT_ESTIMATE_SECONDS - elapsed);
    }

    private void cleanupFinishedJobs() {
        if (jobs.size() < MAX_RETAINED_JOBS) {
            return;
        }
        Instant cutoff = Instant.now().minus(TERMINAL_RETENTION);
        jobs.entrySet().removeIf(entry -> {
            JobState state = entry.getValue();
            return isTerminal(state.status)
                    && state.finishedAt != null
                    && state.finishedAt.isBefore(cutoff);
        });
    }

    private static boolean isRetryable(String status) {
        return "FAILED".equals(status) || "CANCELED".equals(status);
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || isRetryable(status);
    }

    private static final class JobState {
        private final String taskId;
        private final ResourceGenerationRequest request;
        private final List<ResourceGenerationProgressEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final Instant createdAt = Instant.now();
        private volatile String status = "QUEUED";
        private volatile int progressPercent = 0;
        private volatile String message = "资源生成任务已创建";
        private volatile String runId;
        private volatile ResourceGenerationResponse response;
        private volatile String error;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile boolean cancelRequested;
        private volatile FutureTask<Void> task;

        private JobState(String taskId, ResourceGenerationRequest request) {
            this.taskId = taskId;
            this.request = request;
        }
    }

    public record JobSnapshot(
            String taskId,
            Long learningSessionId,
            String status,
            int progressPercent,
            String message,
            String runId,
            List<ResourceGenerationProgressEvent> events,
            ResourceGenerationResponse response,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            int estimatedRemainingSeconds,
            boolean retryable
    ) {
    }
}
