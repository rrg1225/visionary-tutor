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
        name = "learning_path_node",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_learning_path_node_artifact_key",
                columnNames = {"artifact_id", "node_key"}
        )
)
public class LearningPathNode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "node_key", nullable = false, length = 96)
    private String nodeKey;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "resource_type", length = 32)
    private String resourceType;

    @Column(name = "mastery")
    private Integer mastery;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;
}
