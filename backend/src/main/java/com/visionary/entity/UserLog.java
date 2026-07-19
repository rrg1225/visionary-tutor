package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_log")
public class UserLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "learning_phase", length = 64)
    private String learningPhase;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "gmt_created", nullable = false, updatable = false)
    private LocalDateTime gmtCreated;

    @Column(name = "gmt_modified", nullable = false)
    private LocalDateTime gmtModified;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.gmtCreated = now;
        this.gmtModified = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.gmtModified = LocalDateTime.now();
    }
}
