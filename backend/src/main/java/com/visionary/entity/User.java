package com.visionary.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(name = "email", unique = true, length = 128)
    private String email;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "grade_level", length = 32)
    private String gradeLevel;

    @Column(name = "learning_goal", length = 256)
    private String learningGoal;

    @Column(name = "learner_profile_snapshot", columnDefinition = "LONGTEXT")
    private String learnerProfileSnapshot;

    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion = 1;

    @Column(name = "path_version", nullable = false)
    private Integer pathVersion = 1;

    @Column(name = "last_policy_reason", length = 512)
    private String lastPolicyReason;

    /**
     * Serialized emotion / sensory profile snapshot from EmotionProfileAgent.
     */
    @Column(name = "emotion_profile_snapshot", columnDefinition = "TEXT")
    private String emotionProfileSnapshot;

    @Column(name = "previous_guest_id", length = 64)
    private String previousGuestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    public enum UserStatus {
        ACTIVE,
        INACTIVE,
        SUSPENDED
    }
}
