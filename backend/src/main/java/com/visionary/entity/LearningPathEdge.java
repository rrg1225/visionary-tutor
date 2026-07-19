package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(
        name = "learning_path_edge",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_learning_path_edge_artifact_pair",
                columnNames = {"artifact_id", "from_node_key", "to_node_key"}
        )
)
public class LearningPathEdge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "from_node_key", nullable = false, length = 96)
    private String fromNodeKey;

    @Column(name = "to_node_key", nullable = false, length = 96)
    private String toNodeKey;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType = "PREREQUISITE";

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;
}
