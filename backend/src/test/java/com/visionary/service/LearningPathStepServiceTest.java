package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningPathNode;
import com.visionary.entity.LearningSession;
import com.visionary.repository.LearningPathEdgeRepository;
import com.visionary.repository.LearningPathNodeRepository;
import com.visionary.repository.LearningPathStepRepository;
import com.visionary.repository.LearningSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningPathStepServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 2L;
    private static final long ARTIFACT_ID = 3L;

    @Mock
    private LearningPathStepRepository stepRepository;
    @Mock
    private LearningPathNodeRepository nodeRepository;
    @Mock
    private LearningPathEdgeRepository edgeRepository;
    @Mock
    private LearningSessionRepository learningSessionRepository;
    @Mock
    private LearningMasteryPipelineService learningMasteryPipelineService;

    private LearningPathStepService service;

    @BeforeEach
    void setUp() {
        service = new LearningPathStepService(
                stepRepository,
                nodeRepository,
                edgeRepository,
                learningSessionRepository,
                learningMasteryPipelineService,
                new ObjectMapper()
        );
    }

    @Test
    void syncStepsReplacesPreviousArtifactStepsForTheSessionBeforeInsert() {
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        when(learningSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        LearningPathNode first = node(11L, 1, "Foundations");
        LearningPathNode second = node(12L, 2, "Practice");
        when(nodeRepository.findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID))
                .thenReturn(List.of(first, second));

        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId(ARTIFACT_ID);
        artifact.setLearningSessionId(SESSION_ID);

        service.syncStepsFromArtifact(artifact);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.visionary.entity.LearningPathStep>> captor =
                ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(stepRepository);
        order.verify(stepRepository).deleteByUserIdAndLearningSessionId(USER_ID, SESSION_ID);
        order.verify(stepRepository).saveAll(captor.capture());

        assertEquals(2, captor.getValue().size());
        assertEquals(ARTIFACT_ID, captor.getValue().get(0).getArtifactId());
        assertEquals(1, captor.getValue().get(0).getStepOrder());
        assertEquals(2, captor.getValue().get(1).getStepOrder());
    }

    @Test
    void syncStepsKeepsCurrentPathWhenNewArtifactHasNoNodes() {
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        when(learningSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(nodeRepository.findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID)).thenReturn(List.of());

        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId(ARTIFACT_ID);
        artifact.setLearningSessionId(SESSION_ID);

        service.syncStepsFromArtifact(artifact);

        verify(stepRepository, never()).deleteByUserIdAndLearningSessionId(USER_ID, SESSION_ID);
        verify(stepRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    private LearningPathNode node(Long id, int order, String label) {
        LearningPathNode node = new LearningPathNode();
        node.setId(id);
        node.setArtifactId(ARTIFACT_ID);
        node.setLearningSessionId(SESSION_ID);
        node.setOrderIndex(order);
        node.setLabel(label);
        node.setMetadataJson("{\"rationale\":\"test\"}");
        return node;
    }
}
