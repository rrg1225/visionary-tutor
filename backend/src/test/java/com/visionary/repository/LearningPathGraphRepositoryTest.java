package com.visionary.repository;

import com.visionary.entity.LearningPathEdge;
import com.visionary.entity.LearningPathNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class LearningPathGraphRepositoryTest {

    private static final long ARTIFACT_ID = 7001L;
    private static final long SESSION_ID = 9001L;

    @Autowired
    private LearningPathNodeRepository nodeRepository;

    @Autowired
    private LearningPathEdgeRepository edgeRepository;

    @Test
    void bulkDeletesAllowReplacementWithTheSameUniqueGraphKeys() {
        nodeRepository.saveAllAndFlush(List.of(node("n1", 1), node("n2", 2)));
        edgeRepository.saveAndFlush(edge("n1", "n2", 1));

        edgeRepository.deleteByArtifactId(ARTIFACT_ID);
        nodeRepository.deleteByArtifactId(ARTIFACT_ID);

        assertEquals(0, edgeRepository.count());
        assertEquals(0, nodeRepository.count());
        assertDoesNotThrow(() -> {
            nodeRepository.saveAllAndFlush(List.of(node("n1", 1), node("n2", 2)));
            edgeRepository.saveAndFlush(edge("n1", "n2", 1));
        });
        assertEquals(2, nodeRepository.count());
        assertEquals(1, edgeRepository.count());
    }

    private LearningPathNode node(String key, int order) {
        LearningPathNode node = new LearningPathNode();
        node.setArtifactId(ARTIFACT_ID);
        node.setLearningSessionId(SESSION_ID);
        node.setNodeKey(key);
        node.setLabel(key);
        node.setResourceType("");
        node.setOrderIndex(order);
        return node;
    }

    private LearningPathEdge edge(String from, String to, int order) {
        LearningPathEdge edge = new LearningPathEdge();
        edge.setArtifactId(ARTIFACT_ID);
        edge.setLearningSessionId(SESSION_ID);
        edge.setFromNodeKey(from);
        edge.setToNodeKey(to);
        edge.setRelationType("PREREQUISITE");
        edge.setOrderIndex(order);
        return edge;
    }
}
