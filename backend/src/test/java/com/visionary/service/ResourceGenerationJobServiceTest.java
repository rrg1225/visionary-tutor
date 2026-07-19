package com.visionary.service;

import com.visionary.dto.ResourceGenerationProgressEvent;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceGenerationJobServiceTest {

    @Test
    void completesWithProgressAndZeroRemainingEstimate() {
        ResourceGenerationFacade facade = mock(ResourceGenerationFacade.class);
        when(facade.generate(any(), any())).thenAnswer(invocation -> {
            ResourceGenerationProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new ResourceGenerationProgressEvent(
                    "run-1", "GENERATE", "DocAgent", 1, "生成讲解文档", "", 60
            ));
            return new ResourceGenerationResponse("run-1", List.of(), List.of(), "资源已通过审查");
        });
        ResourceGenerationJobService service = new ResourceGenerationJobService(facade, Runnable::run);

        ResourceGenerationJobService.JobSnapshot snapshot = service.start(request());

        assertThat(snapshot.status()).isEqualTo("SUCCEEDED");
        assertThat(snapshot.progressPercent()).isEqualTo(100);
        assertThat(snapshot.estimatedRemainingSeconds()).isZero();
        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.retryable()).isFalse();
    }

    @Test
    void queuedJobCanBeCancelledAndRetriedWithANewTaskId() {
        ResourceGenerationFacade facade = mock(ResourceGenerationFacade.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor holdingExecutor = queued::set;
        ResourceGenerationJobService service = new ResourceGenerationJobService(facade, holdingExecutor);

        ResourceGenerationJobService.JobSnapshot started = service.start(request());
        ResourceGenerationJobService.JobSnapshot cancelled = service.cancel(started.taskId());
        ResourceGenerationJobService.JobSnapshot retried = service.retry(started.taskId());

        assertThat(started.status()).isEqualTo("QUEUED");
        assertThat(cancelled.status()).isEqualTo("CANCELED");
        assertThat(cancelled.retryable()).isTrue();
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.taskId()).isNotEqualTo(started.taskId());
        assertThat(queued.get()).isNotNull();
    }

    private ResourceGenerationRequest request() {
        return new ResourceGenerationRequest(
                42L,
                "卷积神经网络",
                "{}",
                "[]",
                "{}",
                List.of(GeneratedArtifact.ArtifactType.HANDOUT),
                "request-1"
        );
    }
}
